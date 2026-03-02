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
import jakarta.inject.Inject;
import org.apache.commons.vfs2.FileObject;
import org.infinispan.commons.api.BasicCache;
import org.infinispan.commons.api.BasicCacheContainer;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.core.ArchiveScanner;
import org.jboss.pnc.deliverablesanalyzer.core.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.core.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.core.ChecksumService;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.utils.SpdxLicenseUtils;
import org.jboss.pnc.deliverablesanalyzer.model.cache.ArchiveEntry;
import org.jboss.pnc.deliverablesanalyzer.model.cache.ArchiveInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LocalFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class FileChecksumProducerTest {

    private BasicCache<String, ArchiveInfo> fileCache;

    @Inject
    FileChecksumProducer producer;

    @Inject
    BuildConfig config; // Use real config

    @InjectMock
    BasicCacheContainer cacheContainer;

    @InjectMock
    ChecksumService checksumService;

    @InjectMock
    ArchiveScanner archiveScanner;

    @BeforeEach
    void setup() {
        fileCache = mock(BasicCache.class);
        when(cacheContainer.<String, ArchiveInfo> getCache(anyString())).thenReturn(fileCache);

        // Re-init producer to pick up the mocked cache
        try (MockedStatic<SpdxLicenseUtils> mockedUtils = mockStatic(SpdxLicenseUtils.class)) {
            // Stub the heavy methods to return simple/empty values instantly
            mockedUtils.when(SpdxLicenseUtils::getSpdxLicenseMapping).thenReturn(Collections.emptyMap());
            mockedUtils.when(SpdxLicenseUtils::getSPDXLicenseListVersion).thenReturn("Mock-Version");
            mockedUtils.when(SpdxLicenseUtils::getNumberOfSPDXLicenses).thenReturn(0);

            // Run init (which calls the mocked static methods)
            producer.init();
        }
    }

    @Test
    void testProduceWithCacheHit(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Given
        Path testFile = tempDir.resolve("cached-file.zip");
        Files.writeString(testFile, "dummy content");
        String inputPath = testFile.toUri().toString();

        Checksum rootChecksum = new Checksum("root-hash", "cached-file.zip", 100L);
        ArchiveEntry cachedEntry = new ArchiveEntry(
                "inner-hash",
                new LocalFile("inner.txt", 50L),
                Collections.emptyList());
        ArchiveInfo archiveInfo = new ArchiveInfo(Set.of(cachedEntry));

        // Mock ChecksumService to return a root hash
        when(checksumService.checksum(any(FileObject.class), anyString())).thenReturn(rootChecksum);

        // Mock Cache to return a HIT
        when(fileCache.get("root-hash")).thenReturn(archiveInfo);

        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();
        BuildSpecificConfig buildConfig = new BuildSpecificConfig(Collections.emptyList(), Collections.emptyList());

        // When
        producer.produce(inputPath, queue, buildConfig);

        // Then
        // 1. Should NOT perform deep scan
        verify(archiveScanner, never()).scan(any(), anyString(), any(), any(), anyString(), any(), any());

        // 2. Queue should contain the cached entry
        assertEquals(1, queue.size());
        QueueEntry entry = queue.take();
        assertEquals("inner.txt", entry.checksum().getFilename());
    }

    @Test
    void testProduceWithCacheMiss(@TempDir Path tempDir) throws IOException {
        // Given
        Path testFile = tempDir.resolve("new-file.zip");
        Files.writeString(testFile, "content");
        String inputPath = testFile.toUri().toString();

        Checksum rootChecksum = new Checksum("new-hash", "new-file.zip", 100L);

        when(checksumService.checksum(any(FileObject.class), anyString())).thenReturn(rootChecksum);
        // Mock Cache MISS
        when(fileCache.get("new-hash")).thenReturn(null);

        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();
        BuildSpecificConfig buildConfig = new BuildSpecificConfig(Collections.emptyList(), Collections.emptyList());

        // When
        producer.produce(inputPath, queue, buildConfig);

        // Then
        // 1. MUST perform deep scan
        verify(archiveScanner, times(1)).scan(any(), anyString(), any(), any(), anyString(), any(), any());

        // 2. Should update cache after scan
        verify(fileCache, times(1)).put(eq("new-hash"), any(ArchiveInfo.class));
    }

    @Test
    void testProduceFileNotFound(@TempDir Path tempDir) {
        // Given
        String inputPath = tempDir.resolve("non-existent.zip").toUri().toString();
        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();

        // When & Then
        ReasonedException ex = assertThrows(ReasonedException.class, () -> producer.produce(inputPath, queue, null));

        assertEquals(ResultStatus.FAILED, ex.getResult());
        assertTrue(ex.getMessage().contains("does not exist"));
    }
}
