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

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@ApplicationScoped
public class RemoteFileDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteFileDownloader.class);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Determines if the given path requires an HTTP download.
     */
    public boolean isRemoteUrl(String path) {
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase();
        return lowerPath.startsWith("http://") || lowerPath.startsWith("https://");
    }

    /**
     * Downloads a file from a remote HTTP(S) endpoint to a local temporary file. The returned object is AutoCloseable
     * and will automatically delete the file when closed.
     *
     * @param url The remote URL to download.
     * @return An auto-closable wrapper around the downloaded temporary file.
     * @throws IOException If a network or disk error occurs, or if the server returns an error status.
     * @throws InterruptedException If the download thread is interrupted.
     */
    public DownloadedFile downloadToTempFile(String url) throws IOException, InterruptedException {
        LOGGER.info("Downloading remote file to local temp storage: {}", url);

        // Extract the filename from the URL path
        String pathInfo = URI.create(url).getPath();
        String exactFilename = "downloaded-file"; // Safe fallback
        if (pathInfo != null && pathInfo.lastIndexOf('/') != -1) {
            exactFilename = pathInfo.substring(pathInfo.lastIndexOf('/') + 1);
        }
        if (exactFilename.isEmpty()) {
            exactFilename = "downloaded-file";
        }

        // Create a unique sandbox DIRECTORY to prevent concurrent file collisions
        Path tempBaseDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path uniqueJobDir = Files.createTempDirectory(tempBaseDir, "analyzer-");

        // Assemble the target path using the original filename
        Path targetFile = uniqueJobDir.resolve(exactFilename);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(15))
                .build();

        HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(targetFile));

        if (response.statusCode() >= 400) {
            Files.deleteIfExists(targetFile);
            Files.deleteIfExists(uniqueJobDir);
            throw new IOException(
                    "Failed to download file. HTTP status: " + response.statusCode() + " for URL: " + url);
        }

        LOGGER.debug("Successfully downloaded {} to {}", url, targetFile);

        // Pass both the file and the directory so the wrapper cleans up everything
        return new DownloadedFile(targetFile, uniqueJobDir);
    }

    /**
     * AutoCloseable Wrapper
     */
    public static class DownloadedFile implements AutoCloseable {
        private final Path tempFile;
        private final Path tempDir;

        public DownloadedFile(Path tempFile, Path tempDir) {
            this.tempFile = tempFile;
            this.tempDir = tempDir;
        }

        public Path getPath() {
            return tempFile;
        }

        @Override
        public void close() {
            try {
                // Must delete the file first, otherwise the directory deletion will fail
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
                if (tempDir != null) {
                    Files.deleteIfExists(tempDir);
                }
                LOGGER.debug("Cleaned up temporary download sandbox: {}", tempDir);
            } catch (IOException e) {
                LOGGER.warn("Failed to clean up temporary sandbox: {}", tempDir, e);
            }
        }
    }
}
