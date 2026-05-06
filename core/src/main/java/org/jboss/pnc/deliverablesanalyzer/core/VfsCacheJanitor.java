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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class VfsCacheJanitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(VfsCacheJanitor.class);

    private static final String VFS_CACHE = "vfs_cache";
    private static final Path VFS_CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), VFS_CACHE);

    // Runs every hour, only deletes files older than 2 hours to guarantee no active job is using them
    @Scheduled(every = "1h")
    public void sweepAbandonedVfsFiles() {
        if (!Files.exists(VFS_CACHE_DIR) || !Files.isDirectory(VFS_CACHE_DIR)) {
            return;
        }

        LOGGER.info("Starting background sweep of VFS cache: {}", VFS_CACHE_DIR);
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        int deletedCount = 0;

        try (Stream<Path> paths = Files.walk(VFS_CACHE_DIR)) {
            // Collect to a list in reverse order so we delete files inside folders before deleting the folders themselves
            var pathsToDelete = paths
                    .filter(path -> !path.equals(VFS_CACHE_DIR)) // Don't delete the root cache dir itself
                    .sorted(Comparator.reverseOrder()) // Reverse order for deep deletion
                    .toList();

            for (Path path : pathsToDelete) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                if (attrs.lastModifiedTime().toInstant().isBefore(twoHoursAgo)) {
                    Files.deleteIfExists(path);
                    deletedCount++;
                }
            }

            if (deletedCount > 0) {
                LOGGER.info("Successfully cleaned up {} abandoned files/folders from VFS cache.", deletedCount);
            }

        } catch (IOException e) {
            LOGGER.error("Error during scheduled VFS cache cleanup", e);
        }
    }
}
