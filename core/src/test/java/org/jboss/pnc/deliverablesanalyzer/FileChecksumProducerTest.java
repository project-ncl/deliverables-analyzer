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

import io.quarkus.infinispan.client.Remote;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.commons.vfs2.FileObject;
import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.core.ArchiveScanner;
import org.jboss.pnc.deliverablesanalyzer.config.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.core.ChecksumService;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.core.RemoteFileDownloader;
import org.jboss.pnc.deliverablesanalyzer.model.cache.ArchiveEntry;
import org.jboss.pnc.deliverablesanalyzer.model.cache.ArchiveInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LocalFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class FileChecksumProducerTest {

    @Inject
    FileChecksumProducer producer;

    @InjectMock
    RemoteFileDownloader fileDownloader;

    @InjectMock
    @Remote("pnc-archives")
    RemoteCache<String, ArchiveInfo> checksumCache;

    @InjectMock
    ChecksumService checksumService;

    @InjectMock
    ArchiveScanner archiveScanner;

    @Test
    void testProduceWithCacheHit(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Given
        Path testFile = tempDir.resolve("cached-file.zip");
        Files.writeString(testFile, "dummy content");
        String inputPath = testFile.toUri().toString();

        Checksum rootChecksum = new Checksum("root-hash", "root-hash", "root-hash", "cached-file.zip", 100L);
        ArchiveEntry cachedEntry = new ArchiveEntry(
                "inner-hash",
                "inner-hash",
                "inner-hash",
                new LocalFile("inner.txt", 50L),
                Collections.emptyList());
        ArchiveInfo archiveInfo = new ArchiveInfo(List.of(cachedEntry));

        when(fileDownloader.isRemoteUrl(inputPath)).thenReturn(false);
        // Mock ChecksumService to return a root hash
        when(checksumService.checksum(any(FileObject.class), anyString())).thenReturn(rootChecksum);
        // Mock Cache to return a HIT
        when(checksumCache.get("root-hash")).thenReturn(archiveInfo);

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

        Checksum rootChecksum = new Checksum("new-hash", "new-hash", "new-hash", "new-file.zip", 100L);

        when(fileDownloader.isRemoteUrl(inputPath)).thenReturn(false);
        // Mock ChecksumService to return a root hash
        when(checksumService.checksum(any(FileObject.class), anyString())).thenReturn(rootChecksum);
        // Mock Cache MISS
        when(checksumCache.get("new-hash")).thenReturn(null);

        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();
        BuildSpecificConfig buildConfig = new BuildSpecificConfig(Collections.emptyList(), Collections.emptyList());

        // When
        producer.produce(inputPath, queue, buildConfig);

        // Then
        // 1. MUST perform deep scan
        verify(archiveScanner, times(1)).scan(any(), anyString(), any(), any(), anyString(), any(), any());

        // 2. Should update cache after scan
        verify(checksumCache, times(1)).putAsync(eq("new-hash"), any(ArchiveInfo.class));
    }

    @Test
    void testProduceWithRemoteDownload(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Given
        String remoteUrl = "https://example.com/app-1.0.jar";

        // Simulate a file successfully downloaded to a temporary sandbox
        Path fakeSandbox = tempDir.resolve("analyzer-sandbox");
        Files.createDirectories(fakeSandbox);
        Path fakeDownload = fakeSandbox.resolve("app-1.0.jar");
        Files.writeString(fakeDownload, "downloaded jar content");

        // Use the AutoCloseable wrapper
        RemoteFileDownloader.DownloadedFile mockDownloadedFile =
            new RemoteFileDownloader.DownloadedFile(fakeDownload, fakeSandbox);

        // Mock downloader behavior
        when(fileDownloader.isRemoteUrl(remoteUrl)).thenReturn(true);
        when(fileDownloader.downloadToTempFile(remoteUrl)).thenReturn(mockDownloadedFile);

        Checksum rootChecksum = new Checksum("remote-hash", "remote-hash", "remote-hash", "app-1.0.jar", 100L);
        when(checksumService.checksum(any(FileObject.class), anyString())).thenReturn(rootChecksum);
        when(checksumCache.get("remote-hash")).thenReturn(null); // Force deep scan

        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();
        BuildSpecificConfig buildConfig = new BuildSpecificConfig(Collections.emptyList(), Collections.emptyList());

        // When
        producer.produce(remoteUrl, queue, buildConfig);

        // Then
        // Ensure the downloader was actually invoked
        verify(fileDownloader, times(1)).downloadToTempFile(remoteUrl);

        // IMPORTANT: Ensure the scanner was passed the ORIGINAL URL, not the local temp path
        verify(archiveScanner, times(1)).scan(any(), anyString(), any(), any(), eq(remoteUrl), any(), any());
    }

    @Test
    void testProduceFileNotFound(@TempDir Path tempDir) {
        // Given
        String inputPath = tempDir.resolve("non-existent.zip").toUri().toString();
        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();

        when(fileDownloader.isRemoteUrl(inputPath)).thenReturn(false);

        // When & Then
        ReasonedException ex = assertThrows(ReasonedException.class, () -> producer.produce(inputPath, queue, null));

        assertEquals(ResultStatus.FAILED, ex.getResult());
        assertTrue(ex.getMessage().contains("does not exist"));
    }
}
