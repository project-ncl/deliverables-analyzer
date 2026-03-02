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
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LocalFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class ArchiveScannerTest {

    @Inject
    ArchiveScanner archiveScanner;

    @Inject
    BuildConfig config;

    @InjectMock
    ChecksumService checksumService;

    @InjectMock
    LicenseService licenseService;

    private FileSystemManager fsManager;

    @BeforeEach
    void setup() throws IOException {
        fsManager = VFS.getManager();
    }

    @Test
    void testScanRecursionAndQueue(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Given: A structure root/test.txt and root/archive.zip!/inner.txt
        File txtFile = tempDir.resolve("test.txt").toFile();
        Files.writeString(txtFile.toPath(), "content");

        File zipFile = tempDir.resolve("archive.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("inner.txt"));
            zos.write("inner content".getBytes());
            zos.closeEntry();
        }

        FileObject root = fsManager.resolveFile(tempDir.toUri());

        // Mock ChecksumService to return dummy checksums for any file
        when(checksumService.checksum(any(FileObject.class), anyString())).thenAnswer(invocation -> {
            FileObject fo = invocation.getArgument(0);
            return new Checksum("123456", fo.getName().getBaseName(), 100L);
        });

        // Mock LicenseService
        when(licenseService.extractLicensesFromJar(any(), any(), anyString())).thenReturn(Collections.emptyList());
        when(licenseService.getPomLicenses(any(), anyString())).thenReturn(Collections.emptyList());

        // Prepare inputs
        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();
        Map<String, Set<LocalFile>> checksumMap = new HashMap<>();
        Map<String, List<LicenseInfo>> licensesMap = new HashMap<>();
        BuildSpecificConfig buildConfig = new BuildSpecificConfig(Collections.emptyList(), Collections.emptyList());

        // When
        archiveScanner
                .scan(root, root.getName().getPath(), checksumMap, licensesMap, "http://test-url", queue, buildConfig);

        // Then
        // We expect at least 3 entries: test.txt, archive.zip, and inner.txt (inside zip)
        // We verify that 'inner.txt' was found -> recursion worked

        boolean foundInner = false;
        while (!queue.isEmpty()) {
            QueueEntry entry = queue.take();
            if ("inner.txt".equals(entry.checksum().getFilename())) {
                foundInner = true;
            }
        }

        assertTrue(foundInner, "Should have found inner.txt inside archive.zip");
        assertEquals(1, checksumMap.size(), "Should have aggregated checksums (all mocks returned '123456')");
    }
}
