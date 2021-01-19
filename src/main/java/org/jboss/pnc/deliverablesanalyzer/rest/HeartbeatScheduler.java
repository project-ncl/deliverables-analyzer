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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.jboss.pnc.api.dto.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.scheduler.Scheduled;

/**
 * Service, which performs regular heartbeat requests based using subscription style
 *
 * @author Jakub Bartecek &lt;jbartece@redhat.com&gt;
 */
@ApplicationScoped
public class HeartbeatScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeartbeatScheduler.class);

    @Inject
    HttpClient httpClient;

    private Map<String, Request> subscribedRequests = new ConcurrentHashMap<>();

    @Scheduled(every = "{heartbeatPeriod}")
    void performHeartbeats() {
        subscribedRequests.forEach((k, v) -> {
            try {
                httpClient.performHttpRequest(v);
            } catch (Exception e) {
                LOGGER.warn("Heartbeat failed with an exception!", e);
            }
        });
    }

    public void subscribeRequest(String id, Request request) {
        subscribedRequests.put(id, request);
    }

    public void unsubscribeRequest(String id) {
        subscribedRequests.remove(id);
    }
}
