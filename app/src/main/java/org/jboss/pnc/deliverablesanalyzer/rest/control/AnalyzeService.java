/*
 * Copyright (C) 2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.deliverablesanalyzer.rest.control;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisReport;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.common.concurrent.HeartbeatScheduler;
import org.jboss.pnc.deliverablesanalyzer.BuildFinderOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AnalyzeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeService.class);

    @Inject
    ManagedExecutor executor;

    @Inject
    HeartbeatScheduler heartbeatScheduler;

    @Inject
    BuildFinderOrchestrator buildFinderOrchestrator;

    @Inject
    CallbackService callbackService;

    @Inject
    RemoteCacheManager remoteCacheManager;

    private RemoteCache<String, String> cancelEventsCache;
    private DistributedCancelListener cancelListener;

    private final Map<String, CompletableFuture<Void>> runningJobs = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        try {
            cancelEventsCache = remoteCacheManager.getCache("cancel-events");

            if (this.cancelEventsCache == null) {
                LOGGER.error("Cache 'cancel-events' could not be found or created!");
                return;
            }

            cancelListener = new DistributedCancelListener(this);
            cancelEventsCache.addClientListener(cancelListener);
            LOGGER.debug("Registered distributed cancellation listener.");
        } catch (Exception e) {
            LOGGER.error("Failed to register distributed cancellation", e);
        }
    }

    @PreDestroy
    void destroy() {
        if (cancelListener != null) {
            try {
                cancelEventsCache.removeClientListener(cancelListener);
                LOGGER.info("Unregistered distributed cancellation listener.");
            } catch (Exception e) {
                LOGGER.warn("Failed to unregister listener during shutdown.", e);
            }
        }
    }

    public String analyze(AnalyzePayload analyzePayload) {
        LOGGER.info(
                "Analysis request accepted: [urls: {}, config: {}, callback: {}, heartbeat: {}, operationId: {}]",
                analyzePayload.getUrls(),
                analyzePayload.getConfig(),
                analyzePayload.getCallback(),
                analyzePayload.getHeartbeat(),
                analyzePayload.getOperationId());

        if (analyzePayload.getUrls() == null || analyzePayload.getUrls().isEmpty()) {
            throw new BadRequestException("No URL was specified");
        }

        String id = analyzePayload.getOperationId();
        Set<String> urls = new HashSet<>(analyzePayload.getUrls());
        String specificConfig = analyzePayload.getConfig();

        if (analyzePayload.getHeartbeat() != null) {
            heartbeatScheduler.subscribeRequest(id, analyzePayload.getHeartbeat());
        }

        CompletableFuture<Void> future = executor.runAsync(() -> {
            LOGGER.info("Analysis with ID {} initiated. Starting analysis of these URLs: {}", id, urls);

            try {
                AnalysisReport analysisReport = performAnalysis(id, urls, specificConfig);
                handleCallback(id, analyzePayload, analysisReport);
            } finally {
                if (analyzePayload.getHeartbeat() != null) {
                    heartbeatScheduler.unsubscribeRequest(id);
                }
            }
        });

        runningJobs.put(id, future);
        future.whenComplete((u, ex) -> runningJobs.remove(id));

        return id;
    }

    public boolean cancel(String id) {
        try {
            cancelEventsCache.put(id, "CANCEL_REQUESTED");
            LOGGER.info("Broadcasted cancellation request for analysis ID {}", id);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast cancellation for ID {}", id, e);
            return false;
        }
    }

    public void tryCancelLocalJob(String idToCancel) {
        CompletableFuture<Void> future = runningJobs.get(idToCancel);

        // If future is null, this pod ignores the event (it's running on another pod)
        if (future != null) {
            LOGGER.info("Received distributed signal to cancel local job ID {}", idToCancel);

            heartbeatScheduler.unsubscribeRequest(idToCancel);
            boolean cancelled = future.cancel(true);

            if (cancelled) {
                LOGGER.info("Successfully cancelled local analysis ID {}", idToCancel);
            } else {
                LOGGER.warn("Local analysis ID {} could not be cancelled (maybe already finished).", idToCancel);
            }
        }
    }

    private AnalysisReport performAnalysis(String id, Set<String> urls, String specificConfig) {
        AnalysisReport analysisReport = null;

        try {
            List<FinderResult> finderResults = buildFinderOrchestrator.analyze(id, urls, specificConfig);
            analysisReport = new AnalysisReport(finderResults);
            LOGGER.info("Analysis with ID {} finished successfully.", id);
            LOGGER.warn("Analysis ID {} - Analysis Result: {}", id, analysisReport);
        } catch (CancellationException e) {
            LOGGER.info(
                    "Analysis with ID {} cancelled. No callback will be performed. Exception: {}",
                    id,
                    e.toString());
        } catch (ReasonedException e) {
            String errorDescription = e.getMessage() == null ? e.toString() : e.getMessage();
            analysisReport = AnalysisReport.processWithResolution(e.getResult(), e.getExceptionResolution());
            LOGGER.warn(
                    "ErrorId={} Analysis with ID {} failed: {}",
                    e.getErrorId(),
                    id,
                    errorDescription,
                    e.getCause());
        } catch (Throwable e) {
            String errorDescription = e.getMessage() == null ? e.toString() : e.getMessage();
            String errorReason = String.format("Analysis with ID %s failed: %s", id, errorDescription);
            ReasonedException exception = new ReasonedException(ResultStatus.SYSTEM_ERROR, errorReason, e);
            analysisReport = AnalysisReport
                    .processWithResolution(exception.getResult(), exception.getExceptionResolution());
            LOGGER.warn("ErrorId={} Analysis with ID {} failed: {}", exception.getErrorId(), id, errorDescription, e);
        }

        return analysisReport;
    }

    private void handleCallback(String id, AnalyzePayload payload, AnalysisReport report) {
        if (report == null) {
            return; // Analysis cancelled
        }

        if (payload.getCallback() != null) {
            String reportSuccess = report.isSuccess() ? "successfully" : "unsuccessfully";
            try {
                boolean callbackSuccess = callbackService.performCallback(payload.getCallback(), report);
                LOGGER.info(
                        "Analysis with ID {} finished {}. Callback {}.",
                        id,
                        reportSuccess,
                        callbackSuccess ? "performed" : "failed");
            } catch (Exception e) {
                LOGGER.warn(
                        "Analysis with ID {} finished {}, but callback couldn't be performed.",
                        id,
                        reportSuccess,
                        e);
            }
        } else {
            LOGGER.warn("Analysis with ID {} finished, but no callback defined: {}.", id, payload);
        }
    }
}
