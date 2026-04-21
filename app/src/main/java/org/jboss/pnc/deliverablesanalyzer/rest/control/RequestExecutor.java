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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.pnc.api.dto.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class RequestExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestExecutor.class);

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public void executeRequest(Request request, Object entity) throws IOException {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(request.getUri().toString()))
                    .timeout(Duration.ofSeconds(30));

            if (request.getHeaders() != null) {
                request.getHeaders().forEach(header -> requestBuilder.header(header.getName(), header.getValue()));
            }

            if (entity != null) {
                requestBuilder.header("Content-Type", "application/json");
                requestBuilder.method(
                        request.getMethod().toString(),
                        HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(entity)));
            } else {
                requestBuilder.method(request.getMethod().toString(), HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOGGER.warn(
                        "Http request failed. ResponseCode: {}, Entity: {}",
                        response.statusCode(),
                        response.body());
                throw new IOException("Http request failed. ResponseCode: " + response.statusCode());
            }

            LOGGER.debug("Http request sent successfully. ResponseCode: {}", response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request was interrupted", e);
        }
    }
}
