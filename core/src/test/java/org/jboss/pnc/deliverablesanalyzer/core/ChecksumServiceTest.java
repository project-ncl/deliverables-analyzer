/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import jakarta.inject.Inject;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksummedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChecksumServiceTest {

    @Inject
    ChecksumService checksumService;

    private FileSystemManager fsManager;
    private FileObject root;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        fsManager = VFS.getManager();
        root = fsManager.resolveFile(tempDir.toUri());
    }

    @Test
    void testChecksumStandardFile() throws IOException {
        // Given: A standard text file
        String content = "Hello World";
        FileObject file = root.resolveFile("test.txt");
        file.createFile();
        try (OutputStream os = file.getContent().getOutputStream()) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }

        String expectedSha256 = DigestUtils.sha256Hex(content);

        // When
        ChecksummedFile result = checksumService.checksum(file, root.getName().getPath());

        // Then
        assertNotNull(result);
        assertEquals(expectedSha256, result.getSha256Value(), "SHA-256 should match content");
        assertEquals("/test.txt", result.getFilename());
        assertEquals(content.length(), result.getFileSize());
    }

    @Test
    void testChecksumEmptyFile() throws IOException {
        // Given: An empty file
        FileObject file = root.resolveFile("empty.txt");
        file.createFile();

        String expectedSha256 = DigestUtils.sha256Hex("");

        // When
        ChecksummedFile result = checksumService.checksum(file, root.getName().getPath());

        // Then
        assertEquals(expectedSha256, result.getSha256Value());
        assertEquals(0, result.getFileSize());
    }
}
