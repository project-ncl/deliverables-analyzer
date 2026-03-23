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

import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBtype;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import io.quarkus.infinispan.client.Remote;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.pnc.api.deliverablesanalyzer.dto.ArtifactType;
import org.jboss.pnc.deliverablesanalyzer.koji.KojiMultiCallClient;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.cache.KojiArchiveInfoWrapper;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.KojiBuild;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
public class KojiBuildFinderTest {

    @Inject
    KojiBuildFinder kojiBuildFinder;

    @InjectMock
    KojiMultiCallClient kojiClient;

    @InjectMock
    @Remote("koji-archive-cache")
    RemoteCache<String, KojiArchiveInfoWrapper> archiveCache;

    @InjectMock
    @Remote("koji-build-cache")
    RemoteCache<Integer, KojiBuild> buildCache;

    @Test
    void testFindBuilds() throws KojiClientException {
        // Given
        String sha256 = "testSha256";
        String md5 = "testMd5";
        Integer buildId = 100;
        String nvr = "org.test:test-1.0-1";
        Checksum checksum = new Checksum(sha256, md5, "test.jar", 100L);

        QueueEntry entry = new QueueEntry("http://source", checksum, Collections.emptyList());
        ConcurrentHashMap<QueueEntry, Collection<String>> table = new ConcurrentHashMap<>();
        table.put(entry, List.of("test.jar"));

        // Mock Koji Archive Response
        KojiArchiveInfo archiveInfo = new KojiArchiveInfo();
        archiveInfo.setArchiveId(1);
        archiveInfo.setBuildId(buildId);
        archiveInfo.setFilename("test.jar");
        archiveInfo.setChecksum(sha256);
        archiveInfo.setBuildType(KojiBtype.maven);
        archiveInfo.setSize(100);
        archiveInfo.setGroupId("org.test");
        archiveInfo.setArtifactId("test");
        archiveInfo.setVersion("1.0");
        archiveInfo.setExtension("jar");

        when(kojiClient.listArchives(anyList())).thenReturn(List.of(List.of(archiveInfo)));

        // Mock Koji Build Info Response
        KojiBuildInfo buildInfo = new KojiBuildInfo();
        buildInfo.setId(buildId);
        buildInfo.setNvr(nvr);
        buildInfo.setTaskId(1);

        when(kojiClient.getBuilds(anyList())).thenReturn(List.of(buildInfo));

        // Mock Koji Tag Response
        KojiTagInfo tagInfo = new KojiTagInfo();
        tagInfo.setId(1);
        tagInfo.setName("test-tag");

        when(kojiClient.listTagsByIds(anyList())).thenReturn(List.of(List.of(tagInfo)));

        // Mock Infinispan Caches to return null (Forcing the XML-RPC network calls)
        when(archiveCache.get(anyString())).thenReturn(null);
        when(buildCache.get(anyInt())).thenReturn(null);

        // When
        AnalyzerResult results = kojiBuildFinder.findBuilds(table);

        // Then
        assertNotNull(results);
        assertTrue(results.notFoundArtifacts().isEmpty(), "No artifacts should be missing");
        assertTrue(results.foundBuilds().containsKey(String.valueOf(buildId)), "Should contain the Koji build ID");

        AnalyzerBuild resultBuild = results.foundBuilds().get(String.valueOf(buildId));
        assertEquals(nvr, resultBuild.getBuildNvr(), "NVR should be correctly extracted from build details");
        assertFalse(resultBuild.isImport(), "Build with a Task ID should NOT be marked as an import");
        assertEquals(1, resultBuild.getBuiltArtifacts().size());

        // Verify Artifact Mapping
        var artifact = resultBuild.getBuiltArtifacts().iterator().next();
        assertEquals(sha256, artifact.getChecksum().getSha256Value());
        assertEquals(ArtifactType.MAVEN, artifact.getArtifactType());
        assertEquals("org.test", artifact.getArtifactProps().get("groupId"));
    }

    @Test
    void testFindBuildsHandlesEmptyResult() {
        ConcurrentHashMap<QueueEntry, Collection<String>> table = new ConcurrentHashMap<>();
        AnalyzerResult results = kojiBuildFinder.findBuilds(table);

        assertNotNull(results);
        assertTrue(results.foundBuilds().isEmpty());
        assertTrue(results.notFoundArtifacts().isEmpty());
    }
}
