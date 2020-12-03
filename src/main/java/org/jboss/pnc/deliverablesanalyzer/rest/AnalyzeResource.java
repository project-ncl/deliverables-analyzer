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
package org.jboss.pnc.deliverablesanalyzer.rest;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.api.deliverablesanalyzer.api.AnalyzeService;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisResult;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.Finder;
import org.jboss.pnc.deliverablesanalyzer.StatusCache;
import org.jboss.pnc.deliverablesanalyzer.model.FinderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AnalyzeResource implements AnalyzeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeResource.class);

    @Inject
    ManagedExecutor executor;

    @Inject
    StatusCache<String, FinderStatus> statuses;

    @Inject
    Finder finder;

    @Inject
    BuildConfig applicationConfig;

    @Inject
    HeartbeatScheduler heartbeatScheduler;

    @Inject
    HttpClient httpClient;

    @Context
    UriInfo uriInfo;

    @Override
    public Response cancel(String id) {
        heartbeatScheduler.unsubscribeRequest(id);
        if (finder.cancel(id)) {
            return Response.ok().build();
        }

        throw new NotFoundException("There was no operation running to be cancelled");
    }

    @Override
    public Response analyze(AnalyzePayload analyzePayload) {
        List<String> urls = analyzePayload.getUrls();
        LOGGER.info(
                "Analysis request accepted: [urls: {}, config: {}, callback: {}, heartbeat: {}",
                analyzePayload.getUrls(),
                analyzePayload.getConfig(),
                analyzePayload.getCallback(),
                analyzePayload.getHeartbeat());
        BuildConfig specificConfig = validateInputsLoadConfig(urls, analyzePayload.getConfig());

        String id = DigestUtils.sha256Hex(urls.get(0));
        FinderStatus status = new FinderStatus();
        statuses.putIfAbsent(id, status);

        if (analyzePayload.getHeartbeat() != null) {
            heartbeatScheduler.subscribeRequest(id, analyzePayload.getHeartbeat());
        }

        executor.runAsync(() -> {
            LOGGER.info("Analysis with ID {} was initiated. Starting analysis of these URLs: {}", id, urls);
            AnalysisResult analysisResult = null;
            try {
                List<FinderResult> finderResults = finder.find(id, urls, status, status, specificConfig);
                analysisResult = new AnalysisResult(finderResults);
                LOGGER.debug("Analysis finished successfully. Analysis results: {}", analysisResult);
            } catch (CancellationException ce) {
                // The task was cancelled => don't send results using callback
                LOGGER.info("Analysis with ID {} was cancelled. No callback will be performed. Exception: {}", id, ce);
            } catch (Throwable e) {
                analysisResult = new AnalysisResult(e);
                LOGGER.warn("Analysis with ID {} failed due to {}", id, e);
            }

            if (analysisResult != null) {
                if (!performCallback(analyzePayload.getCallback(), analysisResult)) {
                    heartbeatScheduler.unsubscribeRequest(id);
                    LOGGER.info("Analysis with ID {} was finished, but callback couldn't be performed!", id);
                    return;
                }
            }

            heartbeatScheduler.unsubscribeRequest(id);
            LOGGER.info("Analysis with ID {} was successfully finished and callback was performed.", id);
        });

        return Response.ok().type(MediaType.TEXT_PLAIN_TYPE).entity(id).build();
    }

    private boolean performCallback(Request callback, AnalysisResult result) {
        try {
            httpClient.performHttpRequest(callback, result);
            return true;
        } catch (Exception e) {
            try {
                httpClient.performHttpRequest(callback, result);
                return true;
            } catch (Exception ioException) {
                LOGGER.warn("Unable to send results using callback!", ioException);
                return false;
            }
        }
    }

    private BuildConfig validateInputsLoadConfig(List<String> urls, String config) {
        if (urls.isEmpty()) {
            throw new BadRequestException("No URL was specified");
        }
        BuildConfig specificConfig;
        try {
            specificConfig = prepareConfig(config);
        } catch (IOException e) {
            throw new BadRequestException("The provided config couldn't be parsed!", e);
        }
        return specificConfig;
    }

    private BuildConfig prepareConfig(String rawConfig) throws IOException {
        BuildConfig specificConfig = BuildConfig.copy(applicationConfig);

        if (rawConfig != null) {
            BuildConfig config = BuildConfig.load(rawConfig);
            if (config.getExcludes() != null) {
                specificConfig.setExcludes(config.getExcludes());
            }

            if (config.getArchiveExtensions() != null) {
                specificConfig.setArchiveExtensions(config.getArchiveExtensions());
            }

            if (config.getArchiveTypes() != null) {
                specificConfig.setArchiveTypes(config.getArchiveTypes());
            }
        }

        return specificConfig;
    }
}
