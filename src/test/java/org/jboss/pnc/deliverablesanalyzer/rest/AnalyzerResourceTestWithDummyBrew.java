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
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Produces;

import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.build.finder.koji.ClientSession;
import org.jboss.pnc.deliverablesanalyzer.model.AnalyzePayload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AnalyzerResourceTestWithDummyBrew extends AnalyzeResourceTestAbstract {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzerResourceTestWithDummyBrew.class);

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

    public AnalyzerResourceTestWithDummyBrew() throws MalformedURLException {
    }

    @Test
    public void cancelTestSuccessful() throws InterruptedException {
        // Start analysis
        Response response = given()
                .body(new AnalyzePayload(List.of(stubThreeArtsZip(1500)), null, callbackRequest, null))
                .contentType(APPLICATION_JSON)
                .when()
                .post(analyzeUrl)
                .thenReturn();
        assertEquals(200, response.getStatusCode());
        String id = response.getBody().asString();

        Thread.sleep(1000);

        // Cancel the running analysis
        given().when().post("/api/analyze/" + id + "/cancel").then().statusCode(200);
    }

    @Test
    public void cancelTestNotFound() {
        given().when().post("/api/analyze/99999/cancel").then().statusCode(404);
    }

    @Disabled // FIXME - disabled as it causes the tests to run infinitely. The tests passes, but the scheduler doesn't
              // finish.
    @Test
    public void analyzeTestHeartBeat() throws InterruptedException, MalformedURLException {
        // given
        // Setup handler for heartbeat
        String heartbeatPath = "/heartbeat";
        Request heartbeatRequest = new Request("GET", new URL(baseUrl + heartbeatPath));
        wiremock.stubFor(get(urlEqualTo(heartbeatPath)).willReturn(aResponse().withStatus(200)));

        // when
        // Start analysis
        Response response = given()
                .body(new AnalyzePayload(List.of(stubThreeArtsZip(15000)), null, callbackRequest, heartbeatRequest))
                .contentType(APPLICATION_JSON)
                .when()
                .post(analyzeUrl)
                .thenReturn();
        assertEquals(200, response.getStatusCode());
        String id = response.getBody().asString();

        // then
        verifyCallback(() -> wiremock.verify(1, getRequestedFor(urlEqualTo(heartbeatPath))));

        // cleanup
        // Cancel the running analysis
        given().when().post("/api/analyze/" + id + "/cancel").then().statusCode(200);
        Thread.sleep(1000);
    }

    @Test
    public void analyzeTestMalformedUrlDirect() throws InterruptedException {
        // given
        wiremock.stubFor(post(urlEqualTo(callbackRelativePath)).willReturn(aResponse().withStatus(200)));

        // when
        analyzeResource.analyze(new AnalyzePayload(List.of("xxyy:/malformedUrl.zip"), null, callbackRequest, null));

        // then
        verifyCallback(
                () -> wiremock.verify(
                        1,
                        postRequestedFor(urlEqualTo(callbackRelativePath)).withRequestBody(
                                containing("java.net.MalformedURLException: unknown protocol: xxyy"))));
    }

    @Test
    public void analyzeTestMalformedUrlRest() throws InterruptedException {
        wiremock.stubFor(post(urlEqualTo(callbackRelativePath)).willReturn(aResponse().withStatus(200)));

        Response response = given()
                .body(new AnalyzePayload(List.of("xxyy:/malformedUrl.zip"), null, callbackRequest, null))
                .contentType(APPLICATION_JSON)
                .when()
                .post(analyzeUrl)
                .thenReturn();

        // then
        assertEquals(200, response.getStatusCode());
        assertEquals("676017a772b1df2ef4b79e95827fd563f6482c366bb002221cc19903ad75c95f", response.getBody().asString());
        verifyCallback(
                () -> wiremock.verify(
                        1,
                        postRequestedFor(urlEqualTo(callbackRelativePath)).withRequestBody(
                                containing("java.net.MalformedURLException: unknown protocol: xxyy"))));
    }

    @Dependent
    public static class DummyKojiClientSessionProducer {
        @Produces
        public ClientSession createClientSession() {
            LOGGER.info("Using alternate dummy Koji ClientSession");
            return new ClientSession() {
                @Override
                public List<KojiArchiveInfo> listArchives(KojiArchiveQuery query) throws KojiClientException {
                    return null;
                }

                @Override
                public Map<String, KojiArchiveType> getArchiveTypeMap() throws KojiClientException {
                    Map<String, KojiArchiveType> archiveTypeMap = new HashMap<>();
                    archiveTypeMap.put("jar", new KojiArchiveType("jar", List.of("jar"), 1, "jar"));
                    archiveTypeMap.put("jar", new KojiArchiveType("zip", List.of("zip"), 2, "zip"));

                    return archiveTypeMap;
                }

                @Override
                public KojiBuildInfo getBuild(int buildId) throws KojiClientException {
                    return null;
                }

                @Override
                public KojiTaskInfo getTaskInfo(int taskId, boolean request) throws KojiClientException {
                    return null;
                }

                @Override
                public KojiTaskRequest getTaskRequest(int taskId) throws KojiClientException {
                    return null;
                }

                @Override
                public List<KojiTagInfo> listTags(int id) throws KojiClientException {
                    return null;
                }

                @Override
                public void enrichArchiveTypeInfo(List<KojiArchiveInfo> archiveInfos) throws KojiClientException {

                }

                @Override
                public List<List<KojiArchiveInfo>> listArchives(List<KojiArchiveQuery> queries)
                        throws KojiClientException {
                    return null;
                }

                @Override
                public List<KojiBuildInfo> getBuild(List<KojiIdOrName> idsOrNames) throws KojiClientException {
                    return null;
                }

                @Override
                public List<KojiRpmInfo> getRPM(List<KojiIdOrName> idsOrNames) throws KojiClientException {
                    return null;
                }

                @Override
                public List<KojiTaskInfo> getTaskInfo(List<Integer> taskIds, List<Boolean> requests)
                        throws KojiClientException {
                    return null;
                }

                @Override
                public List<List<KojiRpmInfo>> listBuildRPMs(List<KojiIdOrName> idsOrNames) throws KojiClientException {
                    return null;
                }

                @Override
                public List<List<KojiTagInfo>> listTags(List<KojiIdOrName> idsOrNames) throws KojiClientException {
                    return null;
                }
            };
        }
    }

}
