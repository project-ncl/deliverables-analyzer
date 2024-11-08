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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.jboss.pnc.api.dto.Request.Method.GET;
import static org.jboss.pnc.api.dto.Request.Method.POST;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.jboss.pnc.api.dto.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;

/**
 * Tests for simple HTTP client wrapper
 *
 * @author Jakub Bartecek &lt;jbartece@redhat.com&gt;
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpClientTest {
    @Inject
    HttpClient httpClient;

    protected static final int PORT = 8082;

    private final WireMockServer wiremock = new WireMockServer(options().port(PORT));

    @BeforeAll
    void beforeAll() {
        wiremock.start();
    }

    @AfterAll
    void afterAll() {
        wiremock.stop();
    }

    @BeforeEach
    void beforeEach() {
        wiremock.resetAll();
    }

    @Test
    void testSimplePerformHttpRequest() throws Exception {
        // given
        String relativePath = "/testSimplePerformHttpRequest";
        String fullUrl = "http://localhost:" + PORT + relativePath;
        Request request = new Request(GET, new URI(fullUrl));
        wiremock.stubFor(get(urlEqualTo(relativePath)).willReturn(aResponse().withStatus(HTTP_OK)));

        // when
        httpClient.performHttpRequest(request);

        // then
        wiremock.verify(1, getRequestedFor(urlEqualTo(relativePath)));
    }

    @Test
    void testSimplePerformHttpRequestFailsafe() throws URISyntaxException {
        // given
        String relativePath = "/testSimplePerformHttpRequest";
        String fullUrl = "http://localhost:" + PORT + relativePath;
        Request request = new Request(GET, new URI(fullUrl + "anything"));
        wiremock.stubFor(get(urlEqualTo(relativePath)).willReturn(aResponse().withStatus(HTTP_OK)));

        // when - then
        assertThrows(IOException.class, () -> httpClient.performHttpRequest(request));
    }

    @Test
    void testSimplePerformHttpRequestConnectionRefusedFailsafe() throws URISyntaxException {
        // given
        String fullUrl = "http://localhost:80000/";
        Request request = new Request(GET, new URI(fullUrl + "anything"));

        // when - then
        assertThrows(ProcessingException.class, () -> httpClient.performHttpRequest(request));
    }

    @Test
    void testAdvancedPerformHttpRequest() throws Exception {
        // given
        String relativePath = "/testAdvancedPerformHttpRequest";
        String fullUrl = "http://localhost:" + PORT + relativePath;

        Request request = new Request(POST, new URI(fullUrl));

        wiremock.stubFor(post(urlEqualTo(relativePath)).willReturn(aResponse().withStatus(HTTP_OK)));

        // when
        httpClient.performHttpRequest(request, new TestPayload(1, "str"));

        // then
        wiremock.verify(
                1,
                postRequestedFor(urlEqualTo(relativePath))
                        .withRequestBody(equalToJson("{\"a\" : 1, \"b\" : \"str\"}")));
    }

    record TestPayload(Integer a, String b) {
    }
}
