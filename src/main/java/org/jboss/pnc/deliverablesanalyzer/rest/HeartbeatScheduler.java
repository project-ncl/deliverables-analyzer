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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import org.jboss.pnc.api.dto.HeartbeatConfig;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.common.concurrent.MDCScheduledThreadPoolExecutor;
import org.jboss.pnc.common.concurrent.NamedThreadFactory;
import org.jboss.pnc.deliverablesanalyzer.rest.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.oidc.client.OidcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Service, which performs regular heartbeat requests based using subscription style
 *
 * @author Jakub Bartecek &lt;jbartece@redhat.com&gt;
 * @author Dustin Kut Moy Cheung &lt;dcheung@redhat.com&gt;
 */
@ApplicationScoped
public class HeartbeatScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final ScheduledExecutorService executor = new MDCScheduledThreadPoolExecutor(
            1,
            new NamedThreadFactory("heartbeat"));

    @Inject
    HttpClient httpClient;

    @Inject
    OidcClient oidcClient;

    private final Map<String, Future<?>> subscribedRequests = new ConcurrentHashMap<>();

    public void subscribeRequest(String id, HeartbeatConfig heartbeatConfig) {
        Future<?> beat = this.executor.scheduleAtFixedRate(
                () -> this.sendHeartbeat(id, heartbeatConfig.getRequest()),
                0L,
                heartbeatConfig.getDelay(),
                heartbeatConfig.getDelayTimeUnit());

        subscribedRequests.put(id, beat);
    }

    public void unsubscribeRequest(String id) {
        Future<?> toRemove = subscribedRequests.remove(id);

        if (toRemove != null) {
            toRemove.cancel(false);
        }
    }

    private void sendHeartbeat(String id, Request heartbeatRequest) {

        try {
            List<Request.Header> headers = heartbeatRequest.getHeaders();
            List<Request.Header> existingAuthHeaders = new ArrayList<>();

            for (Request.Header header : headers) {
                if (header.getName().equals(HttpHeaders.AUTHORIZATION)) {
                    existingAuthHeaders.add(header);
                }
            }

            // remove any Authorization headers
            headers.removeAll(existingAuthHeaders);

            // Add an authorization header with a fresh token
            String accessToken = oidcClient.getTokens().await().atMost(Duration.ofMinutes(1)).getAccessToken();
            headers.add(new Request.Header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

            this.httpClient.performHttpRequest(heartbeatRequest);
        } catch (BadRequestException e) {
            LOGGER.warn("Heartbeat task no longer exists in target service!", e);

            // avoid never ending spam of target service on 404/400 which are non-recoverable
            unsubscribeRequest(id);
        } catch (Exception e) {
            LOGGER.warn("Heartbeat failed with an exception!", e);
        }
    }
}
