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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        Checksum result = checksumService.checksum(file, root.getName().getPath());

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
        Checksum result = checksumService.checksum(file, root.getName().getPath());

        // Then
        assertEquals(expectedSha256, result.getSha256Value());
        assertEquals(0, result.getFileSize());
    }

    @Test
    void testRpmExtensionTriggersRpmLogic() throws IOException {
        // Given: A file ending in .rpm (but with invalid content)
        FileObject file = root.resolveFile("fake.rpm");
        file.createFile();
        try (OutputStream os = file.getContent().getOutputStream()) {
            os.write("fake rpm content".getBytes(StandardCharsets.UTF_8));
        }

        // When & Then: We expect IOException because the service tries to parse it as an RPM stream
        // This proves the "if (rpm)" branch was taken.
        assertThrows(IOException.class, () -> {
            checksumService.checksum(file, root.getName().getPath());
        });
    }
}
