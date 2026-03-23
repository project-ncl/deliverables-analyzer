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
package org.jboss.pnc.deliverablesanalyzer.core;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncClient;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.BuildConfigurationRevision;
import org.jboss.pnc.dto.ProductMilestone;
import org.jboss.pnc.dto.ProductVersion;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.pnc.enums.BuildType;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
class PncBuildFinderTest {

    @Inject
    PncBuildFinder pncBuildFinder;

    @InjectMock
    PncClient pncClient;

    @Test
    void testFindBuilds() {
        // Given
        String sha256 = "testSha256";
        String md5 = "testMd5";
        String buildId = "100";
        Checksum checksum = new Checksum(sha256, md5, "test.jar", 100L);

        QueueEntry entry = new QueueEntry("http://source", checksum, Collections.emptyList());
        ConcurrentHashMap<QueueEntry, Collection<String>> table = new ConcurrentHashMap<>();
        table.put(entry, List.of("test.jar"));

        // Mock PNC Artifact Response
        ProductMilestone milestone = ProductMilestone.builder().id("50").build();
        Build build = Build.builder()
            .id(buildId)
            .productMilestone(milestone)
            .buildConfigRevision(BuildConfigurationRevision.builder().buildType(BuildType.MVN).build())
            .build();
        Artifact artifact = Artifact.builder()
            .id("1")
            .identifier("")
            .sha256(sha256)
            .build(build)
            .artifactQuality(ArtifactQuality.VERIFIED)
            .build();

        when(pncClient.getArtifactsBySha256(sha256)).thenReturn(List.of(artifact));

        // Mock Metadata Response
        ProductVersion version = ProductVersion.builder().id("200").version("1.0").build();
        when(pncClient.getProductVersion("50")).thenReturn(version);
        // getBuildPushReport returns null/void in mock by default, which is fine

        // When
        AnalyzerResult results = pncBuildFinder.findBuilds(table);

        // Then
        assertNotNull(results);
        assertTrue(results.foundBuilds().containsKey(buildId));

        AnalyzerBuild resultBuild = results.foundBuilds().get(buildId);
        assertEquals(1, resultBuild.getBuiltArtifacts().size());
        assertEquals(sha256, resultBuild.getBuiltArtifacts().iterator().next().getChecksum().getSha256Value());
    }

    @Test
    void testFindBuildsHandlesEmptyResult() {
        ConcurrentHashMap<QueueEntry, Collection<String>> table = new ConcurrentHashMap<>();
        AnalyzerResult results = pncBuildFinder.findBuilds(table);
        assertTrue(results.foundBuilds().isEmpty());
        assertTrue(results.notFoundArtifacts().isEmpty());
    }
}
