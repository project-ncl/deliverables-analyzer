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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.inject.Inject;
import javax.ws.rs.ProcessingException;

import org.jboss.pnc.api.dto.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for simple HTTP client wrapper
 *
 * @author Jakub Bartecek &lt;jbartece@redhat.com&gt;
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HttpClientTest {

    @Inject
    HttpClient httpClient;

    private WireMockServer wiremock = new WireMockServer(options().port(8082));

    @BeforeAll
    public void beforeAll() {
        wiremock.start();
    }

    @AfterAll
    public void afterAll() {
        wiremock.stop();
    }

    @BeforeEach
    public void beforeEach() {
        wiremock.resetAll();
    }

    @Test
    public void testSimplePerformHttpRequest() throws Exception {
        // given
        String relativePath = "/testSimplePerformHttpRequest";
        String fullUrl = "http://localhost:8082" + relativePath;
        Request request = new Request("GET", new URL(fullUrl));
        wiremock.stubFor(get(urlEqualTo(relativePath)).willReturn(aResponse().withStatus(200)));

        // when
        httpClient.performHttpRequest(request);

        // then
        wiremock.verify(1, getRequestedFor(urlEqualTo(relativePath)));
    }

    @Test
    public void testSimplePerformHttpRequestFailsafe() throws MalformedURLException {
        // given
        String relativePath = "/testSimplePerformHttpRequest";
        String fullUrl = "http://localhost:8082" + relativePath;
        Request request = new Request("GET", new URL(fullUrl + "anything"));
        wiremock.stubFor(get(urlEqualTo(relativePath)).willReturn(aResponse().withStatus(200)));

        // when - then
        assertThrows(IOException.class, () -> {
            httpClient.performHttpRequest(request);
        });
    }

    @Test
    public void testSimplePerformHttpRequestConnectionRefusedFailsafe() throws MalformedURLException {
        // given
        String fullUrl = "http://localhost:80000/";
        Request request = new Request("GET", new URL(fullUrl + "anything"));

        // when - then
        assertThrows(ProcessingException.class, () -> {
            httpClient.performHttpRequest(request);
        });
    }

    @Test
    public void testAdvancedPerformHttpRequest() throws Exception {
        // given
        String relativePath = "/testAdvancedPerformHttpRequest";
        String fullUrl = "http://localhost:8082" + relativePath;

        Request request = new Request("POST", new URL(fullUrl));

        wiremock.stubFor(post(urlEqualTo(relativePath)).willReturn(aResponse().withStatus(200)));

        // when
        httpClient.performHttpRequest(request, new TestPayload(1, "str"));

        // then
        wiremock.verify(
                1,
                postRequestedFor(urlEqualTo(relativePath))
                        .withRequestBody(equalToJson("{\"a\" : 1, \"b\" : \"str\"}")));
    }

    static class TestPayload {
        private Integer a;
        private String b;

        TestPayload(Integer a, String b) {
            this.a = a;
            this.b = b;
        }

        public Integer getA() {
            return a;
        }

        public String getB() {
            return b;
        }
    }
}
