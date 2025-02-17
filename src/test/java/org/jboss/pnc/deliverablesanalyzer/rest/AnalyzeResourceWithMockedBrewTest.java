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
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.jboss.pnc.build.finder.core.JSONUtils.dumpString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;

import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junitpioneer.jupiter.RestoreSystemProperties;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.RequestListener;
import com.github.tomakehurst.wiremock.http.RequestMethod;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

/**
 * @author Jakub Bartecek
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyzeResourceWithMockedBrewTest extends AbstractAnalyzeResourceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeResourceWithMockedBrewTest.class);

    AnalyzeResourceWithMockedBrewTest() throws IOException {

    }

    public static class LoggingRequestListener implements RequestListener {
        @Override
        public void requestReceived(
                com.github.tomakehurst.wiremock.http.Request request,
                com.github.tomakehurst.wiremock.http.Response response) {
            RequestMethod method = request.getMethod();
            String url = request.getUrl();
            String body = request.getBodyAsString();
            int port = request.getPort();
            HttpHeaders headers = request.getHeaders();
            String protocol = request.getProtocol();

            LOGGER.info(
                    "Received request: method={}, url={}, protocol={}, port={}, headers={}, body={}",
                    method,
                    url,
                    protocol,
                    port,
                    headers,
                    body);
        }
    }

    @Test
    @SetSystemProperty(key = "org.spdx.useJARLicenseInfoOnly", value = "true")
    @RestoreSystemProperties
    void analyzeTestOKSimple() throws InterruptedException, JsonProcessingException {
        // given
        // callback
        WIREMOCK.addMockServiceRequestListener(new LoggingRequestListener());
        WIREMOCK.stubFor(
                post(urlEqualTo(CALLBACK_RELATIVE_PATH))
                        .willReturn(aResponse().withBodyFile("threeArtsAnalysis.json").withStatus(HTTP_OK)));

        // Remote servers stubs
        WireMockServer pncServer = new WireMockServer(
                options().dynamicPort().usingFilesUnderClasspath("analyzeTestOKSimple/pnc"));
        pncServer.addMockServiceRequestListener(new LoggingRequestListener());
        WireMockServer brewHub = new WireMockServer(
                options().dynamicPort().usingFilesUnderClasspath("analyzeTestOKSimple/brewHub"));
        brewHub.addMockServiceRequestListener(new LoggingRequestListener());

        try {
            pncServer.start();
            System.setProperty("pnc.url", pncServer.baseUrl());
            brewHub.start();
            System.setProperty("koji.hub.url", brewHub.baseUrl());

            // when
            Response response = given().body(
                    new AnalyzePayload(
                            "1234",
                            List.of(stubThreeArtsZip(1)),
                            dumpString(testConfigJson),
                            callbackRequest,
                            null))
                    .contentType(APPLICATION_JSON)
                    .when()
                    .post(ANALYZE_URL)
                    .thenReturn();

            // then
            assertEquals(jakarta.ws.rs.core.Response.Status.OK.getStatusCode(), response.getStatusCode());
            String jsonMatchString = "$.results[*].builds[*].artifacts[*].licenses[?(@.spdxLicenseId == 'Apache-2.0')]";

            verifyCallback(
                    () -> WIREMOCK.verify(
                            1,
                            postRequestedFor(urlEqualTo(CALLBACK_RELATIVE_PATH))
                                    .withRequestBody(containing("\"success\":true"))
                                    .withRequestBody(matchingJsonPath(jsonMatchString))
                                    .withRequestBody(containing("\"spdxLicenseId\":\"Apache-2.0\""))));
        } finally {
            pncServer.stop();
            brewHub.stop();
        }
    }
}
