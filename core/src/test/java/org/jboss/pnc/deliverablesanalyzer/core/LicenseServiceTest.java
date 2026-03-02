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
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LicenseServiceTest {

    @Inject
    LicenseService licenseService;

    private FileSystemManager fsManager;
    private FileObject root;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        fsManager = VFS.getManager();
        root = fsManager.resolveFile(tempDir.toUri());
    }

    @Test
    void testGetPomLicenses() throws IOException {
        // Given: A valid pom.xml with an Apache-2.0 license
        FileObject pom = root.resolveFile("pom.xml");
        pom.createFile();
        String pomContent = "<project>" + "  <modelVersion>4.0.0</modelVersion>" + "  <groupId>com.test</groupId>"
                + "  <artifactId>test-artifact</artifactId>" + "  <version>1.0.0</version>" + "  <licenses>"
                + "    <license>" + "      <name>Apache-2.0</name>"
                + "      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>" + "    </license>" + "  </licenses>"
                + "</project>";

        try (OutputStream os = pom.getContent().getOutputStream()) {
            os.write(pomContent.getBytes(StandardCharsets.UTF_8));
        }

        // When
        List<LicenseInfo> licenses = licenseService.getPomLicenses(pom, root.getName().getPath());

        // Then
        assertFalse(licenses.isEmpty(), "Should find at least one license");
        LicenseInfo license = licenses.get(0);
        assertEquals("Apache-2.0", license.getName());
        assertEquals("http://www.apache.org/licenses/LICENSE-2.0.txt", license.getUrl());
    }

    @Test
    void testExtractLicensesFromJarChildren() throws IOException {
        // Given: A simulated JAR structure with a LICENSE.txt file
        FileObject metaInf = root.resolveFile("META-INF");
        metaInf.createFolder();

        FileObject licenseFile = metaInf.resolveFile("LICENSE.txt");
        licenseFile.createFile();
        try (OutputStream os = licenseFile.getContent().getOutputStream()) {
            os.write("This is a license text".getBytes(StandardCharsets.UTF_8));
        }

        // When: We act as if 'root' is the JAR and pass 'LICENSE.txt' as a child found inside it
        List<FileObject> children = List.of(licenseFile);
        List<LicenseInfo> results = licenseService.extractLicensesFromJar(root, children, root.getName().getPath());

        // Then
        assertFalse(results.isEmpty());
        // Verify it picked up the license file
        assertTrue(results.stream().anyMatch(l -> l.getName().contains("LICENSE.txt")));
    }

    @Test
    void testMalformedPomThrowsException() throws IOException {
        // Given: An invalid XML file
        FileObject pom = root.resolveFile("bad_pom.xml");
        pom.createFile();
        try (OutputStream os = pom.getContent().getOutputStream()) {
            os.write("<project><unclosedTag>".getBytes(StandardCharsets.UTF_8));
        }

        // When & Then
        assertThrows(IOException.class, () -> {
            licenseService.getPomLicenses(pom, root.getName().getPath());
        });
    }
}
