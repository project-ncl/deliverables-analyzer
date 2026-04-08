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

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisReport;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.deliverablesanalyzer.core.ChecksumService;
import org.jboss.pnc.deliverablesanalyzer.license.LicenseExtractor;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncClient;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.rest.control.CallbackService;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.BuildConfigurationRevision;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.pnc.enums.BuildType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(AnalyzeResourceIT.NoCacheProfile.class)
public class AnalyzeResourceIT {

    @InjectMock
    PncClient pncClient;

    @InjectMock
    CallbackService callbackService;

    @InjectMock
    LicenseExtractor licenseExtractor;

    @InjectMock
    ChecksumService checksumService;

    public static class NoCacheProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("analyzer.disable-cache", "true");
        }
    }

    @Test
    void testEndToEndAnalysis(@TempDir Path tempDir) throws IOException {
        when(licenseExtractor.extractLicensesFromJar(any(), any(), any())).thenReturn(Collections.emptyList());
        when(licenseExtractor.getPomLicenses(any(), any())).thenReturn(Collections.emptyList());

        String mockedSha256 = "sha256-111111";
        String mockedSha1 = "sha1-111111";
        String mockedMd5 = "md5-111111";
        when(checksumService.checksum(any(), any())).thenAnswer(invocation -> {
            // Return a dummy checksum for any file scanned (like README.txt)
            return new Checksum(mockedSha256, mockedSha1, mockedMd5, "README.txt", 100L);
        });

        // Create a real dummy file to analyze
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(testFile))) {
            zos.putNextEntry(new ZipEntry("README.txt"));
            zos.write("content".getBytes());
            zos.closeEntry();
        }
        String fileUri = testFile.toURI().toString();

        // Mock PNC Response
        Build mockBuild = Build.builder()
                .id("build-100")
                .buildConfigRevision(BuildConfigurationRevision.builder().id("rev-1").buildType(BuildType.MVN).build())
                .build();
        Artifact mockArtifact = Artifact.builder()
                .id("art-1")
                .sha256(mockedSha256)
                .filename("test-artifact.jar")
                .artifactQuality(ArtifactQuality.VERIFIED)
                .identifier("test")
                .size(100L)
                .build(mockBuild)
                .build();

        when(pncClient.getArtifactsBySha256(mockedSha256)).thenReturn(List.of(mockArtifact));

        // Call the REST API
        AnalyzePayload payload = AnalyzePayload.builder()
                .operationId("integration-test-1")
                .urls(List.of(fileUri))
                .callback(new Request(Request.Method.POST, URI.create("http://dummy-callback")))
                .build();

        given().contentType(ContentType.JSON).body(payload).when().post("/api/analyze").then().statusCode(200);

        // Wait for Async Completion
        ArgumentCaptor<AnalysisReport> reportCaptor = ArgumentCaptor.forClass(AnalysisReport.class);

        // Verify callbackService receives the correct Request object
        verify(callbackService, timeout(10000).times(1)).performCallback(any(Request.class), reportCaptor.capture());

        // Check the Final Report
        AnalysisReport report = reportCaptor.getValue();

        assertNotNull(report);
        assertTrue(report.isSuccess(), "Report should be successful");
        assertEquals(1, report.getResults().size());

        FinderResult result = report.getResults().get(0);
        assertEquals(fileUri, result.getUrl().toString());
        assertFalse(result.getBuilds().isEmpty(), "Should have found the build we mocked");
        assertEquals("build-100", result.getBuilds().iterator().next().getPncId());
    }

    @Test
    void testEndToEndAnalysisFailure(@TempDir Path tempDir) {
        String badUrl = tempDir.resolve("does-not-exist.jar").toUri().toString();

        AnalyzePayload payload = AnalyzePayload.builder()
                .operationId("fail-test-1")
                .urls(List.of(badUrl))
                .callback(new Request(Request.Method.POST, URI.create("http://dummy-callback")))
                .build();

        given().contentType(ContentType.JSON).body(payload).when().post("/api/analyze").then().statusCode(200);

        ArgumentCaptor<AnalysisReport> reportCaptor = ArgumentCaptor.forClass(AnalysisReport.class);

        verify(callbackService, timeout(5000).times(1)).performCallback(any(Request.class), reportCaptor.capture());

        AnalysisReport report = reportCaptor.getValue();
        assertFalse(report.isSuccess());
    }
}
