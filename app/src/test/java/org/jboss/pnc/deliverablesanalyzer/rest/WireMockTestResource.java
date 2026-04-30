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

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;

import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class WireMockTestResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        // Start WireMock on a dynamic port
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        // Configure the static client so 'stubFor' works in the test classes
        configureFor(wireMockServer.port());

        // Return the URL so we can inject it into our tests if needed
        return Map.of("wiremock.url", wireMockServer.baseUrl(), "wiremock.port", String.valueOf(wireMockServer.port()));
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    // Helper to get the running server instance if needed
    public WireMockServer getServer() {
        return wireMockServer;
    }
}
