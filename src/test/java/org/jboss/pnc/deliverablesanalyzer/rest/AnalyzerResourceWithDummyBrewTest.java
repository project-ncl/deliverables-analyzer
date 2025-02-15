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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.jboss.pnc.api.dto.Request.Method.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.dto.HeartbeatConfig;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.build.finder.koji.ClientSession;
import org.jboss.pnc.deliverablesanalyzer.model.AnalyzeResponse;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveQuery;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveType;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiIdOrName;
import com.redhat.red.build.koji.model.xmlrpc.KojiRpmInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTaskInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTaskRequest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

/**
 * @author Jakub Bartecek
 */
@QuarkusTest
@Disabled
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyzerResourceWithDummyBrewTest extends AbstractAnalyzeResourceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzerResourceWithDummyBrewTest.class);

    AnalyzerResourceWithDummyBrewTest() throws URISyntaxException {
    }

    @Test
    void cancelTestSuccessful() throws InterruptedException, JsonProcessingException {
        // Start analysis
        Response response = given()
                .body(
                        new AnalyzePayload(
                                "1234",
                                List.of(stubThreeArtsZip(TIMEOUT_MILLISECONDS)),
                                null,
                                callbackRequest,
                                null))
                .contentType(APPLICATION_JSON)
                .when()
                .post(ANALYZE_URL)
                .thenReturn();
        assertEquals(jakarta.ws.rs.core.Response.Status.OK.getStatusCode(), response.getStatusCode());

        LOGGER.warn("AnalyzeResponse: {}", response.getBody().asString());

        AnalyzeResponse analyzeResponse = getAnalyzeResponse(response.getBody().asString());
        LOGGER.warn("AnalyzeResponse: {}", analyzeResponse);

        Thread.sleep(1000);

        // Cancel the running analysis
        given().when()
                .post(analyzeResponse.getCancelRequest().getUri())
                .then()
                .statusCode(jakarta.ws.rs.core.Response.Status.OK.getStatusCode());
    }

    @Test
    void cancelTestNotFound() {
        given().when()
                .post("/api/analyze/99999/cancel")
                .then()
                .statusCode(jakarta.ws.rs.core.Response.Status.NOT_FOUND.getStatusCode());
    }

    @Disabled // FIXME - disabled as it causes the tests to run infinitely. The tests passes, but the scheduler doesn't
    // finish.
    @Test
    void analyzeTestHeartBeat() throws InterruptedException, JsonProcessingException, URISyntaxException {
        // given
        // Setup handler for heartbeat
        String heartbeatPath = "/heartbeat";
        Request heartbeatRequest = new Request(GET, new URI(WIREMOCK.baseUrl() + heartbeatPath));
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig(heartbeatRequest, 100L, TimeUnit.MILLISECONDS);
        WIREMOCK.stubFor(get(urlEqualTo(heartbeatPath)).willReturn(aResponse().withStatus(HTTP_OK)));

        // when
        // Start analysis
        Response response = given().body(
                new AnalyzePayload(
                        "1234",
                        List.of(stubThreeArtsZip(TIMEOUT_MILLISECONDS)),
                        null,
                        callbackRequest,
                        heartbeatConfig))
                .contentType(APPLICATION_JSON)
                .when()
                .post(ANALYZE_URL)
                .thenReturn();
        assertEquals(jakarta.ws.rs.core.Response.Status.OK.getStatusCode(), response.getStatusCode());
        String id = getAnalysisId(response.getBody().asString());

        // then
        verifyCallback(() -> WIREMOCK.verify(1, getRequestedFor(urlEqualTo(heartbeatPath))));

        // cleanup
        // Cancel the running analysis
        given().when()
                .post("/api/analyze/" + id + "/cancel")
                .then()
                .statusCode(jakarta.ws.rs.core.Response.Status.OK.getStatusCode());
        Thread.sleep(1000);
    }

    @Test
    void analyzeTestMalformedUrlDirect() throws InterruptedException, URISyntaxException {
        // given
        WIREMOCK.stubFor(post(urlEqualTo(CALLBACK_RELATIVE_PATH)).willReturn(aResponse().withStatus(HTTP_OK)));

        // when
        try (jakarta.ws.rs.core.Response response = analyzeResource
                .analyze(new AnalyzePayload("1234", List.of("xxyy:/malformedUrl.zip"), null, callbackRequest, null))) {
            assertEquals(jakarta.ws.rs.core.Response.Status.OK.getStatusCode(), response.getStatus());
        }

        // then
        verifyCallback(
                () -> WIREMOCK.verify(
                        1,
                        postRequestedFor(urlEqualTo(CALLBACK_RELATIVE_PATH)).withRequestBody(
                                containing("java.net.MalformedURLException: unknown protocol: xxyy"))));
    }

    @Test
    void analyzeTestMalformedUrlRest() throws InterruptedException {
        WIREMOCK.stubFor(post(urlEqualTo(CALLBACK_RELATIVE_PATH)).willReturn(aResponse().withStatus(HTTP_OK)));

        Response response = given()
                .body(new AnalyzePayload("1234", List.of("xxyy:/malformedUrl.zip"), null, callbackRequest, null))
                .contentType(APPLICATION_JSON)
                .when()
                .post(ANALYZE_URL)
                .thenReturn();

        // then
        assertEquals(jakarta.ws.rs.core.Response.Status.OK.getStatusCode(), response.getStatusCode());
        assertEquals("676017a772b1df2ef4b79e95827fd563f6482c366bb002221cc19903ad75c95f", response.getBody().asString());
        verifyCallback(
                () -> WIREMOCK.verify(
                        1,
                        postRequestedFor(urlEqualTo(CALLBACK_RELATIVE_PATH)).withRequestBody(
                                containing("java.net.MalformedURLException: unknown protocol: xxyy"))));
    }

    // TODO: this messes the AnalyzeResourceWithMockedBrewTest. we need to only inject this for this test alone !!!
    // @Dependent
    public static class DummyKojiClientSessionProducer {
        // @Produces
        public ClientSession createClientSession() {
            LOGGER.info("Using alternate dummy Koji ClientSession");
            return new ClientSession() {
                @Override
                public List<KojiArchiveInfo> listArchives(KojiArchiveQuery query) {
                    return null;
                }

                @Override
                public Map<String, KojiArchiveType> getArchiveTypeMap() {
                    Map<String, KojiArchiveType> archiveTypeMap = new HashMap<>();
                    archiveTypeMap.put("jar", new KojiArchiveType("jar", List.of("jar"), 1, "jar"));
                    archiveTypeMap.put("zip", new KojiArchiveType("zip", List.of("zip"), 2, "zip"));

                    return archiveTypeMap;
                }

                @Override
                public KojiBuildInfo getBuild(int buildId) {
                    return null;
                }

                @Override
                public KojiTaskInfo getTaskInfo(int taskId, boolean request) {
                    return null;
                }

                @Override
                public KojiTaskRequest getTaskRequest(int taskId) {
                    return null;
                }

                @Override
                public List<KojiTagInfo> listTags(int id) {
                    return null;
                }

                @Override
                public void enrichArchiveTypeInfo(List<KojiArchiveInfo> archiveInfos) {

                }

                @Override
                public List<List<KojiArchiveInfo>> listArchives(List<KojiArchiveQuery> queries) {
                    return null;
                }

                @Override
                public List<KojiBuildInfo> getBuild(List<KojiIdOrName> idsOrNames) {
                    return null;
                }

                @Override
                public List<KojiRpmInfo> getRPM(List<KojiIdOrName> idsOrNames) {
                    return null;
                }

                @Override
                public List<KojiTaskInfo> getTaskInfo(List<Integer> taskIds, List<Boolean> requests) {
                    return null;
                }

                @Override
                public List<List<KojiRpmInfo>> listBuildRPMs(List<KojiIdOrName> idsOrNames) {
                    return null;
                }

                @Override
                public List<List<KojiTagInfo>> listTags(List<KojiIdOrName> idsOrNames) {
                    return null;
                }
            };
        }
    }

}
