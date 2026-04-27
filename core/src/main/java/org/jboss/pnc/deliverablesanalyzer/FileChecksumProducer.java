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
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.core.ArchiveScanner;
import org.jboss.pnc.deliverablesanalyzer.config.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.config.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.core.ChecksumService;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.core.RemoteFileDownloader;
import org.jboss.pnc.deliverablesanalyzer.model.cache.ArchiveEntry;
import org.jboss.pnc.deliverablesanalyzer.model.cache.ArchiveInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksumGroup;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LocalFile;
import org.jboss.pnc.deliverablesanalyzer.utils.AnalyzerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FileChecksumProducer {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileChecksumProducer.class);

    @Inject
    BuildConfig buildConfig;

    @Inject
    RemoteFileDownloader remoteFileDownloader;

    @Inject
    FileSystemManager fileSystemManager;

    @Inject
    @Remote("pnc-archives")
    RemoteCache<String, ArchiveInfo> checksumCache;

    @Inject
    ChecksumService checksumService;

    @Inject
    ArchiveScanner archiveScanner;

    @PostConstruct
    public void init() {
        if (buildConfig.disableCache()) {
            LOGGER.info("Cache disabled via configuration");
            this.checksumCache = null;
        }
    }

    /**
     * Scans the provided input path for files, calculates their checksums, and puts them into the provided blocking
     * queue.
     *
     * @param inputPath URL or a file path to scan.
     * @param queue The shared blocking queue to populate.
     * @param buildSpecificConfig Config for builds
     */
    public void produce(String inputPath, BlockingQueue<QueueEntry> queue, BuildSpecificConfig buildSpecificConfig) {
        if (queue == null) {
            throw new IllegalStateException("Queue has not been set in the Producer!");
        }

        LOGGER.debug("Producing file checksums to queue for {}", inputPath);
        scanAndChecksumFiles(inputPath, queue, buildSpecificConfig);
    }

    private void scanAndChecksumFiles(
            String inputPath,
            BlockingQueue<QueueEntry> queue,
            BuildSpecificConfig buildSpecificConfig) {
        LOGGER.info("Scanning files for {}", inputPath);

        try {
            checkInterrupted("Producer cancelled before input: " + inputPath);

            String vfsResolutionPath = inputPath;

            try (RemoteFileDownloader.DownloadedFile downloadedFile = remoteFileDownloader.isRemoteUrl(inputPath)
                    ? remoteFileDownloader.downloadToTempFile(inputPath)
                    : null) {
                if (downloadedFile != null) {
                    vfsResolutionPath = downloadedFile.getPath().toUri().toString();
                }

                try (FileObject fileObject = resolveFile(vfsResolutionPath, inputPath)) {
                    // Determine the root path for relative path calculation
                    String rootPath = AnalyzerUtils.calculateRootPath(fileObject);

                    // Compute root checksum
                    Checksum rootChecksum = checksumCache != null ? checksumService.checksum(fileObject, rootPath)
                            : null;

                    // Try to load from cache
                    boolean checksumInCache = false;
                    if (!buildConfig.disableCache() && rootChecksum != null) {
                        checksumInCache = loadFromCache(fileObject, rootPath, rootChecksum, inputPath, queue);
                    }

                    // Perform deep scan - finds children, hashes them, handles archives
                    if (!checksumInCache) {
                        performDeepScanAndCacheResult(
                                fileObject,
                                rootPath,
                                rootChecksum,
                                inputPath,
                                queue,
                                buildSpecificConfig);
                    }
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw mapToReasonedException(e, inputPath);
        }
    }

    /**
     * Iterates through required checksum types and attempts to load them from the persistent cache. If found, the type
     * is removed from 'checksumTypesToCheck' so we don't recalculate it.
     */
    private boolean loadFromCache(
            FileObject fo,
            String rootPath,
            Checksum rootChecksum,
            String inputPath,
            BlockingQueue<QueueEntry> queue) throws IOException {
        if (buildConfig.disableCache())
            return false;

        String cacheKey = rootChecksum.getSha256Value() != null ? rootChecksum.getSha256Value()
                : rootChecksum.getMd5Value();

        if (cacheKey == null || checksumCache == null)
            return false;

        ArchiveInfo archiveInfo = checksumCache.get(cacheKey);
        if (archiveInfo != null) {
            for (ArchiveEntry entry : archiveInfo.entries()) {
                Checksum checksum = Checksum
                        .create(entry.sha256Checksum(), entry.sha1Checksum(), entry.md5Checksum(), entry.file());

                try {
                    LOGGER.debug("Adding checksum {} for file {} to queue", checksum, entry.file());
                    queue.put(new QueueEntry(inputPath, checksum, entry.licenses()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Producer interrupted while loading from cache");
                }
            }
            logCacheHit(fo, rootPath, cacheKey, archiveInfo.entries().size());
            return true;
        } else {
            logCacheMiss(fo, rootPath, cacheKey);
            return false;
        }
    }

    /**
     * If we couldn't find everything in the cache, we scan the file/directory physically, and then save the results
     * back to the cache for next time.
     */
    private void performDeepScanAndCacheResult(
            FileObject fileObject,
            String rootPath,
            Checksum rootChecksum,
            String inputPath,
            BlockingQueue<QueueEntry> queue,
            BuildSpecificConfig buildSpecificConfig) throws IOException {

        Map<ChecksumGroup, Set<LocalFile>> jobMap = new HashMap<>();
        Map<String, List<LicenseInfo>> licensesMap = new ConcurrentHashMap<>();

        logFindingChecksums(fileObject, rootPath);

        // Find files, process inner archives, and calculate missing checksums
        archiveScanner.scan(fileObject, rootPath, jobMap, licensesMap, inputPath, queue, buildSpecificConfig);

        // Save the newly found structure to the cache
        if (!buildConfig.disableCache() && checksumCache != null && rootChecksum != null) {
            updateCacheWithNewScan(rootChecksum, jobMap, licensesMap);
        }
    }

    private void updateCacheWithNewScan(
            Checksum rootChecksum,
            Map<ChecksumGroup, Set<LocalFile>> jobMap,
            Map<String, List<LicenseInfo>> licensesMap) throws IOException {
        String cacheKey = rootChecksum.getSha256Value() != null ? rootChecksum.getSha256Value()
                : rootChecksum.getMd5Value();

        if (cacheKey == null) {
            throw new IOException("Neither SHA256 nor MD5 checksum found after scan");
        }

        List<ArchiveEntry> entries = new ArrayList<>();

        for (Map.Entry<ChecksumGroup, Set<LocalFile>> entry : jobMap.entrySet()) {
            String sha256Checksum = entry.getKey().sha256();
            String sha1Checksum = entry.getKey().sha1();
            String md5Checksum = entry.getKey().md5();
            for (LocalFile file : entry.getValue()) {
                List<LicenseInfo> licenseInfos = licensesMap.getOrDefault(file.filename(), Collections.emptyList());
                entries.add(new ArchiveEntry(sha256Checksum, sha1Checksum, md5Checksum, file, licenseInfos));
            }
        }

        checksumCache.putAsync(cacheKey, new ArchiveInfo(entries));
    }

    private FileObject resolveFile(String resolutionPath, String inputPath) throws IOException {
        FileObject fileObject;

        try {
            fileObject = fileSystemManager.resolveFile(URI.create(resolutionPath));
        } catch (IllegalArgumentException | FileSystemException e) {
            // Fallback to local path if URI creation fails or VFS doesn't recognize it
            Path path = Path.of(resolutionPath);
            if (!Files.exists(path) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IOException("Input path " + path + " does not exist, is not a file, or is not readable", e);
            }
            fileObject = fileSystemManager.resolveFile(path.toUri());
        }

        // Explicitly check existence for the resolved URI
        if (!fileObject.exists()) {
            throw new IOException("Input path " + inputPath + " does not exist");
        }

        if (LOGGER.isInfoEnabled()) {
            if (fileObject.isFile()) {
                LOGGER.info("Analyzing: {} ({})", inputPath, AnalyzerUtils.byteCountToDisplaySize(fileObject));
            } else {
                LOGGER.info("Analyzing: {}", inputPath);
            }
        }

        return fileObject;
    }

    public void cleanupVfsCache() {
        try {
            Optional<Path> cleanedPath = AnalyzerUtils.cleanupVfsCache();
            if (LOGGER.isDebugEnabled()) {
                if (cleanedPath.isPresent()) {
                    LOGGER.debug("Cleaned up VFS cache at: {}", cleanedPath.get());
                } else {
                    LOGGER.debug("No VFS cache cleanup needed (directory absent or empty).");
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Cleaning up VFS cache failed: {}", e.getMessage());
        }
    }

    private void checkInterrupted(String message) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(message);
        }
    }

    private RuntimeException mapToReasonedException(Exception e, String inputPath) {
        return switch (e) {
            case ReasonedException re -> re;
            case CancellationException ce -> ce;
            case InterruptedException ie -> new CancellationException("Producer interrupted");
            case Exception ex when ex.getCause() instanceof InterruptedException -> new CancellationException("Producer interrupted");
            case Exception ex when ex.getMessage() != null && ex.getMessage().contains("does not exist") -> new ReasonedException(ResultStatus.FAILED, ex.getMessage(), "Please check the URL.", ex);
            case IllegalArgumentException iae -> new ReasonedException(ResultStatus.FAILED, "Invalid input URI: " + inputPath, "Please check the URL.", iae);
            case FileSystemException fse -> new ReasonedException(ResultStatus.FAILED, "Invalid input URI: " + inputPath, "Please check the URL.", fse);
            // Default to System Error for everything else
            case null, default -> new ReasonedException(ResultStatus.SYSTEM_ERROR, "System failed to process input: " + inputPath, e);
        };
    }

    // -------------------------------------------------------------------------
    // Logging Helpers
    // -------------------------------------------------------------------------

    private void logCacheHit(FileObject fo, String rootPath, String value, int size) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Loaded {} checksums for file: {} (checksum: {}) from cache",
                    size,
                    AnalyzerUtils.normalizePath(fo, rootPath),
                    value);
        }
    }

    private void logCacheMiss(FileObject fo, String rootPath, String value) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("File: {} (checksum: {}) not found in cache", AnalyzerUtils.normalizePath(fo, rootPath), value);
        }
    }

    private void logFindingChecksums(FileObject fo, String rootPath) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Finding checksums for file: {}", AnalyzerUtils.normalizePath(fo, rootPath));
        }
    }
}
