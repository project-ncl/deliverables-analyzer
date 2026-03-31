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

import io.quarkus.oidc.client.OidcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisReport;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.deliverablesanalyzer.utils.MdcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class CallbackService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallbackService.class);

    @Inject
    RequestExecutor requestExecutor;

    @Inject
    OidcClient oidcClient;

    @ConfigProperty(name = "callback.auth.enabled", defaultValue = "true")
    boolean authEnabled;

    @Retry(maxRetries = 2, delay = 1000, delayUnit = ChronoUnit.MILLIS)
    public boolean performCallback(Request callback, AnalysisReport analysisReport) {
        if (callback == null)
            return false;

        mergeHttpHeaders(callback, MdcUtils.mdcToMapWithHeaderKeys());
        if (authEnabled) {
            addAuthenticationHeaderToCallback(callback);
        }

        try {
            requestExecutor.executeRequest(callback, analysisReport);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Exception when performing callback: {}", e.toString());
        }
        return false;
    }

    private void addAuthenticationHeaderToCallback(Request callback) {
        String accessToken = oidcClient.getTokens().await().atMost(Duration.ofMinutes(1)).getAccessToken();
        callback.getHeaders().add(new Request.Header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    /**
     * Given a request and a map of HTTP headers, add the HTTP headers to the request if not already in the request
     *
     * @param request the request
     * @param httpHeaders the HTTP headers
     */
    private static void mergeHttpHeaders(Request request, Map<String, String> httpHeaders) {
        if (httpHeaders == null || httpHeaders.isEmpty())
            return;

        Set<String> existingHeaderKeys = request.getHeaders()
                .stream()
                .map(Request.Header::getName)
                .collect(Collectors.toSet());

        httpHeaders.forEach((k, v) -> {
            if (!existingHeaderKeys.contains(k)) {
                request.getHeaders().add(new Request.Header(k, v));
            }
        });
    }
}
