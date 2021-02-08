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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static java.net.HttpURLConnection.HTTP_OK;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URISyntaxException;
import java.util.List;

import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

/**
 * @author Jakub Bartecek
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AnalyzeResourceTestWithMockedBrew extends AnalyzeResourceTestAbstract {

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

    public AnalyzeResourceTestWithMockedBrew() throws URISyntaxException {
    }

    @Test
    public void analyzeTestOKSimple() throws InterruptedException {
        // given
        // callback
        wiremock.stubFor(post(urlEqualTo(callbackRelativePath)).willReturn(aResponse().withStatus(HTTP_OK)));

        // Remote servers stubs
        WireMockServer pncServer = new WireMockServer(
                options().port(8083).usingFilesUnderClasspath("analyzeTestOKSimple/pnc"));
        pncServer.start();

        WireMockServer brewHub = new WireMockServer(
                options().port(8084).usingFilesUnderClasspath("analyzeTestOKSimple/brewHub"));
        brewHub.start();

        // when
        Response response = given().body(new AnalyzePayload(List.of(stubThreeArtsZip(1)), null, callbackRequest, null))
                .contentType(APPLICATION_JSON)
                .when()
                .post(analyzeUrl)
                .thenReturn();

        // then
        String id = response.getBody().asString();
        assertEquals(200, response.getStatusCode());

        verifyCallback(
                () -> wiremock.verify(
                        1,
                        postRequestedFor(urlEqualTo(callbackRelativePath))
                                .withRequestBody(containing("\"success\":true"))));

        // cleanup
        pncServer.stop();
        brewHub.stop();
    }
}
