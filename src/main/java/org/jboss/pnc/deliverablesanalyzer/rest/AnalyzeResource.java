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
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisReport;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.Finder;
import org.jboss.pnc.deliverablesanalyzer.StatusCache;
import org.jboss.pnc.deliverablesanalyzer.model.AnalyzeResponse;
import org.jboss.pnc.deliverablesanalyzer.model.FinderStatus;
import org.jboss.pnc.deliverablesanalyzer.utils.MdcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.oidc.client.OidcClient;

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

    @Inject
    OidcClient oidcClient;

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
    public Response analyze(AnalyzePayload analyzePayload) throws URISyntaxException {
        List<String> urls = analyzePayload.getUrls();
        LOGGER.info(
                "Analysis request accepted: [urls: {}, config: {}, callback: {}, heartbeat: {}, operationId: {}]",
                analyzePayload.getUrls(),
                analyzePayload.getConfig(),
                analyzePayload.getCallback(),
                analyzePayload.getHeartbeat(),
                analyzePayload.getOperationId());
        BuildConfig specificConfig = validateInputsLoadConfig(urls, analyzePayload.getConfig());

        String id = analyzePayload.getOperationId();
        FinderStatus status = new FinderStatus();
        statuses.putIfAbsent(id, status);

        if (analyzePayload.getHeartbeat() != null) {
            heartbeatScheduler.subscribeRequest(id, analyzePayload.getHeartbeat());
        }

        // add any mdc values from request to the callback if needed
        if (analyzePayload.getCallback() != null) {
            mergeHttpHeaders(analyzePayload.getCallback(), MdcUtils.mdcToMapWithHeaderKeys());
        }
        executor.runAsync(() -> {
            LOGGER.info("Analysis with ID {} was initiated. Starting analysis of these URLs: {}", id, urls);
            AnalysisReport analysisReport = null;
            try {
                List<FinderResult> finderResults = finder.find(id, urls, status, status, specificConfig);
                analysisReport = new AnalysisReport(finderResults);
                LOGGER.debug("Analysis finished successfully. Analysis results: {}", analysisReport);
            } catch (CancellationException ce) {
                // The task was cancelled => don't send results using callback
                LOGGER.info("Analysis with ID {} was cancelled. No callback will be performed. Exception: {}", id, ce);
            } catch (Throwable e) {
                analysisReport = new AnalysisReport();
                LOGGER.warn(
                        "Analysis with ID {} failed due to {}",
                        id,
                        e.getMessage() == null ? e.toString() : e.getMessage(),
                        e);
            }

            try {
                if (analysisReport != null) {
                    if (analyzePayload.getCallback() == null) {
                        LOGGER.warn(
                                "Analysis with ID {} finished but no callback defined for request {}.",
                                id,
                                analyzePayload);
                        return;
                    } else if (!performCallback(analyzePayload.getCallback(), analysisReport)) {
                        LOGGER.info("Analysis with ID {} was finished, but callback couldn't be performed!", id);
                        return;
                    }
                }

                LOGGER.info(
                        "Analysis with ID {} was {} finished and callback was performed.",
                        id,
                        analysisReport.isSuccess() ? "successfully" : "unsuccessfully");
            } finally {
                if (analyzePayload.getHeartbeat() != null) {
                    heartbeatScheduler.unsubscribeRequest(id);
                }
            }

        });

        return Response.ok().type(MediaType.APPLICATION_JSON).entity(createAnalyzeResponse(id)).build();
    }

    private AnalyzeResponse createAnalyzeResponse(String id) throws URISyntaxException {
        String cancelUrl = uriInfo.getAbsolutePath() + "/" + id + "/cancel";
        return new AnalyzeResponse(id, new Request(Request.Method.POST, new URI(cancelUrl)));
    }

    private boolean performCallback(org.jboss.pnc.api.dto.Request callback, AnalysisReport result) {

        addAuthenticationHeaderToCallback(callback);

        try {
            httpClient.performHttpRequest(callback, result);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Exception when performing Callback with: " + e.toString());
            LOGGER.warn("Retrying");
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

    /**
     * Given a request and a map of http headers, add the http headers to the request if not already in the request
     *
     * @param request
     * @param httpHeaders
     */
    private static void mergeHttpHeaders(Request request, Map<String, String> httpHeaders) {

        List<Request.Header> callbackHeaders = request.getHeaders();
        Set<String> existingHeaderKeys = callbackHeaders.stream()
                .map(Request.Header::getName)
                .collect(Collectors.toSet());

        for (Map.Entry<String, String> entry : httpHeaders.entrySet()) {
            if (!existingHeaderKeys.contains(entry.getKey())) {
                callbackHeaders.add(new Request.Header(entry.getKey(), entry.getValue()));
            }
        }
    }

    private void addAuthenticationHeaderToCallback(org.jboss.pnc.api.dto.Request callback) {
        String accessToken = oidcClient.getTokens().await().atMost(Duration.ofMinutes(1)).getAccessToken();
        List<Request.Header> headers = callback.getHeaders();
        headers.add(new Request.Header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

}
