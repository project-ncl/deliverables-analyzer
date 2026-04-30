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
package org.jboss.pnc.deliverablesanalyzer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.deliverablesanalyzer.config.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.config.ConfigParser;
import org.jboss.pnc.deliverablesanalyzer.core.ChecksumService;
import org.jboss.pnc.deliverablesanalyzer.koji.KojiBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.license.LicenseExtractor;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.rest.WireMockTestResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

@QuarkusTest
@QuarkusTestResource(WireMockTestResource.class)
@TestProfile(AnalyzeCallbackIT.NoCacheProfile.class)
public class AnalyzeCallbackIT {

    private static final String ANALYZE_URL = "/api/analyze";
    private static final String CALLBACK_RELATIVE_PATH = "/callback/complete";

    @ConfigProperty(name = "wiremock.url")
    String wireMockUrl;

    @ConfigProperty(name = "wiremock.port")
    int wireMockPort;

    @InjectMock
    ConfigParser configParser;

    @InjectMock
    LicenseExtractor licenseExtractor;

    @InjectMock
    ChecksumService checksumService;

    @InjectMock
    PncBuildFinder pncBuildFinder;

    @InjectMock
    KojiBuildFinder kojiBuildFinder;

    public static class NoCacheProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("analyzer.disable-cache", "true");
        }
    }

    @BeforeEach
    void setupWireMockClient() {
        WireMock.configureFor(wireMockPort);
        WireMock.reset();
    }

    @Test
    void testFullRoundTripWithWireMock() throws IOException {
        // Mock internal services
        when(configParser.parseConfig(any()))
                .thenReturn(new BuildSpecificConfig(Collections.emptyList(), Collections.emptyList()));
        when(licenseExtractor.extractLicensesFromJar(any(), any(), any())).thenReturn(Collections.emptyList());
        when(licenseExtractor.getPomLicenses(any(), any())).thenReturn(Collections.emptyList());

        when(pncBuildFinder.findBuilds(any())).thenReturn(AnalyzerResult.empty());
        when(kojiBuildFinder.findBuilds(any())).thenReturn(AnalyzerResult.empty());

        // Ensure we return a checksum so the analysis has "results" to report
        when(checksumService.checksum(any(), any()))
                .thenAnswer(inv -> new Checksum("sha256-111111", "sha1-111111", "md5-111111", "test.txt", 100L));

        // Prepare Wiremock
        // Serve the JAR file (Download phase)
        byte[] jarContent = createDummyZip();
        stubFor(
                head(urlEqualTo("/downloads/test-artifact.jar")).willReturn(
                        aResponse().withStatus(200).withHeader("Content-Type", "application/java-archive")));
        stubFor(
                get(urlEqualTo("/downloads/test-artifact.jar")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/java-archive")
                                .withBody(jarContent)));

        // Accept the Callback (Completion phase)
        stubFor(post(urlEqualTo(CALLBACK_RELATIVE_PATH)).willReturn(aResponse().withStatus(200)));

        // Execute Request
        String downloadUrl = wireMockUrl + "/downloads/test-artifact.jar";
        String callbackUrl = wireMockUrl + CALLBACK_RELATIVE_PATH;

        AnalyzePayload payload = AnalyzePayload.builder()
                .operationId("wiremock-test-1")
                .urls(List.of(downloadUrl))
                .callback(new Request(Request.Method.POST, URI.create(callbackUrl)))
                .build();

        given().contentType(ContentType.JSON).body(payload).when().post(ANALYZE_URL).then().statusCode(200);

        // Verify Callback

        // Use Awaitility to wait for the async operation to hit WireMock
        await().atMost(10, SECONDS).pollInterval(1, SECONDS).untilAsserted(() -> {
            // Verify WireMock received the POST request
            WireMock.verify(
                    postRequestedFor(urlEqualTo(CALLBACK_RELATIVE_PATH))
                            .withHeader("Content-Type", containing("application/json"))
                            // Verify the JSON body contains expected data
                            .withRequestBody(matchingJsonPath("$.results[0].id", equalTo("wiremock-test-1")))
                            .withRequestBody(matchingJsonPath("$.success", equalTo("true"))));
        });
    }

    private byte[] createDummyZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("test.txt"));
            zos.write("content".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
