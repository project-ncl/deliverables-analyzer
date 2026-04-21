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
import jakarta.inject.Inject;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.vfs2.FileExtensionSelector;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.InvertIncludeFileSelector;
import org.jboss.pnc.deliverablesanalyzer.config.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.config.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.license.LicenseExtractor;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksumGroup;
import org.jboss.pnc.deliverablesanalyzer.utils.AnalyzerUtils;
import org.jboss.pnc.deliverablesanalyzer.utils.MavenUtils;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LocalFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

@ApplicationScoped
public class ArchiveScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveScanner.class);

    private static final String BANG_SLASH = "!/";
    private static final String[] JARS_TO_IGNORE = { "-sources.jar" + BANG_SLASH, "-javadoc.jar" + BANG_SLASH,
            "-tests.jar" + BANG_SLASH };
    private static final String JAR_URI = ".jar" + BANG_SLASH;
    private static final Set<String> NON_ARCHIVE_SCHEMES = Set.of("tmp", "res", "ram", "file", "http", "https");
    private static final Set<String> JAR_EXTENSIONS = Set
            .of("jar", "war", "rar", "ear", "sar", "kar", "jdocbook", "jdocbook-style", "plugin");

    @Inject
    BuildConfig buildConfig;

    @Inject
    ChecksumService checksumService;

    @Inject
    LicenseExtractor licenseExtractor;

    /**
     * Recursively scans a file/directory, calculating checksums and handling archives.
     */
    public void scan(
            FileObject fileObject,
            String rootPath,
            Map<ChecksumGroup, Set<LocalFile>> checksumMap,
            Map<String, List<LicenseInfo>> licensesMap,
            String inputPath,
            BlockingQueue<QueueEntry> queue,
            BuildSpecificConfig buildSpecificConfig) throws IOException {
        listChildren(fileObject, rootPath, checksumMap, licensesMap, inputPath, queue, 0, buildSpecificConfig);
    }

    private void listChildren(
            FileObject fileObject,
            String rootPath,
            Map<ChecksumGroup, Set<LocalFile>> checksumMap,
            Map<String, List<LicenseInfo>> licensesMap,
            String inputPath,
            BlockingQueue<QueueEntry> queue,
            int currentLevel,
            BuildSpecificConfig buildSpecificConfig) throws IOException {
        List<FileObject> pomFiles = new ArrayList<>();
        List<FileObject> localFiles = new ArrayList<>();

        try {
            // Discovery: Find children, combine for iteration
            List<FileObject> allFiles = findAndSortChildren(fileObject, pomFiles, localFiles);

            // License Handling (Main JAR)
            if (isMainJar(fileObject)) {
                List<LicenseInfo> licenses = licenseExtractor.extractLicensesFromJar(fileObject, allFiles, rootPath);
                if (!licenses.isEmpty()) {
                    // Normalize the path as the key
                    String key = AnalyzerUtils.licensePath(fileObject, rootPath);
                    licensesMap.computeIfAbsent(key, k -> new ArrayList<>()).addAll(licenses);
                }
            }

            for (FileObject file : allFiles) {
                if (file.isFile()) {
                    // POM Processing
                    if (MavenUtils.isPom(file) || MavenUtils.isPomXml(file)) {
                        try {
                            List<LicenseInfo> licenses = licenseExtractor.getPomLicenses(file, rootPath);
                            String key = AnalyzerUtils.licensePath(file, rootPath);
                            licensesMap.computeIfAbsent(key, k -> new ArrayList<>()).addAll(licenses);
                        } catch (IOException e) {
                            if (LOGGER.isErrorEnabled()) {
                                LOGGER.error(
                                        "Error parsing POM file {}: {}",
                                        file,
                                        AnalyzerUtils.getAllErrorMessages(e));
                            }
                        }
                    }

                    // Archive Recursion
                    if (isArchive(file)) {
                        int nextLevel = currentLevel + 1;
                        if (shouldListArchive(file, nextLevel)) {
                            // Recurse strictly sequentially to avoid VFS threading issues
                            processArchive(
                                    file,
                                    rootPath,
                                    checksumMap,
                                    licensesMap,
                                    inputPath,
                                    queue,
                                    nextLevel,
                                    buildSpecificConfig);
                        }
                    }

                    // Checksum Task & Queue
                    if (shouldChecksum(file, buildSpecificConfig)) {
                        Checksum checksum = checksumService.checksum(file, rootPath);
                        handleChecksum(checksum, checksumMap, licensesMap, inputPath, queue);
                    }
                }
            }
        } finally {
            closeFileObjects(localFiles);
            closeFileObjects(pomFiles);
        }
    }

    private void handleChecksum(
            Checksum checksum,
            Map<ChecksumGroup, Set<LocalFile>> checksumMap,
            Map<String, List<LicenseInfo>> licensesMap,
            String inputPath,
            BlockingQueue<QueueEntry> queue) {

        if (checksum == null) {
            return;
        }

        // Update Map, Attach Licenses and Queue
        ChecksumGroup key = new ChecksumGroup(
                checksum.getSha256Value(),
                checksum.getSha1Value(),
                checksum.getMd5Value());
        checksumMap.computeIfAbsent(key, k -> new HashSet<>())
                .add(new LocalFile(checksum.getFilename(), checksum.getFileSize()));

        List<LicenseInfo> licenses = licensesMap.getOrDefault(checksum.getFilename(), Collections.emptyList());
        Checksum fullChecksum = Checksum.create(
                checksum.getSha256Value(),
                checksum.getSha1Value(),
                checksum.getMd5Value(),
                new LocalFile(checksum.getFilename(), checksum.getFileSize()));

        if (queue != null) {
            try {
                LOGGER.debug("Adding checksum {} for file {} to queue", fullChecksum, checksum.getFilename());
                queue.put(new QueueEntry(inputPath, fullChecksum, licenses));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Interrupted while waiting for checksums");
            }
        }
    }

    private void processArchive(
            FileObject file,
            String rootPath,
            Map<ChecksumGroup, Set<LocalFile>> checksumMap,
            Map<String, List<LicenseInfo>> licensesMap,
            String inputPath,
            BlockingQueue<QueueEntry> queue,
            int nextLevel,
            BuildSpecificConfig buildSpecificConfig) {

        FileSystemManager fsManager = file.getFileSystem().getFileSystemManager();
        FileObject archiveFs = null;

        try {
            // Create layered file system
            archiveFs = fsManager.createFileSystem(file.getName().getExtension(), file);
            // Recurse
            listChildren(
                    archiveFs,
                    rootPath,
                    checksumMap,
                    licensesMap,
                    inputPath,
                    queue,
                    nextLevel,
                    buildSpecificConfig);
        } catch (IOException e) {
            LOGGER.warn("Unable to process archive: {}", file.getName().getFriendlyURI(), e);
        } finally {
            if (archiveFs != null) {
                fsManager.closeFileSystem(archiveFs.getFileSystem());
            }
        }
    }

    // --- Helpers ---

    private boolean shouldListArchive(FileObject fo, int level) {
        // Allow recursion if recursion is enabled globally
        // || it matches specific heuristics (Level 1 "Distribution" or Level 2 "Tarball")
        try {
            return !buildConfig.disableRecursion() || isDistributionArchive(fo, level) || isTarArchive(fo, level);
        } catch (FileSystemException e) {
            LOGGER.warn("Error checking archive type: {}", fo.getName(), e);
            return false;
        }
    }

    private boolean isDistributionArchive(FileObject fo, int level) {
        // If we are at the top level (1), and it's NOT a Java archive (like a jar/war),
        // it might be a distribution zip. Look inside.
        return level == 1 && !isJavaArchive(fo);
    }

    private boolean isTarArchive(FileObject fo, int level) throws FileSystemException {
        // If we are at level 2, check if we are inside a Tarball structure.
        FileObject parent = fo.getParent();
        return level == 2 && parent.isFolder() && parent.getName().getFriendlyURI().endsWith(BANG_SLASH)
                && parent.getChildren().length == 1;
    }

    private boolean isJavaArchive(FileObject fo) {
        return FilenameUtils.isExtension(fo.getName().getBaseName(), JAR_EXTENSIONS);
    }

    private List<FileObject> findAndSortChildren(FileObject fo, List<FileObject> pomFiles, List<FileObject> localFiles)
            throws IOException {
        FileExtensionSelector pomSelector = new FileExtensionSelector("pom");
        fo.findFiles(pomSelector, true, pomFiles);
        fo.findFiles(new InvertIncludeFileSelector(pomSelector), true, localFiles);

        List<FileObject> allFiles = new ArrayList<>(pomFiles);
        allFiles.addAll(localFiles);
        return allFiles;
    }

    private boolean shouldChecksum(FileObject fo, BuildSpecificConfig buildSpecificConfig) {
        // Return true if the extension is allowed && the file path is not blocked by any regex patterns (not excluded)
        return isExtensionAllowed(fo.getName().getExtension(), buildSpecificConfig)
                && !isExcludedByPattern(fo.getName().getFriendlyURI(), buildSpecificConfig);
    }

    private boolean isExtensionAllowed(String extension, BuildSpecificConfig buildSpecificConfig) {
        List<String> allowedExtensions = buildSpecificConfig.getArchiveExtensions();
        // If list is empty, ALL extensions are allowed
        if (allowedExtensions.isEmpty()) {
            return true;
        }
        return allowedExtensions.contains(extension);
    }

    private boolean isExcludedByPattern(String uri, BuildSpecificConfig buildSpecificConfig) {
        List<Pattern> excludes = buildSpecificConfig.getExcludes();
        if (excludes.isEmpty()) {
            return false;
        }
        // Check compiled patterns
        return excludes.stream().anyMatch(pattern -> pattern.matcher(uri).matches());
    }

    private void closeFileObjects(List<FileObject> files) {
        if (files == null) {
            return;
        }
        for (FileObject f : files) {
            try {
                f.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isMainJar(FileObject fo) {
        String name = fo.getPublicURIString();
        return name.endsWith(JAR_URI) && Arrays.stream(JARS_TO_IGNORE).noneMatch(name::endsWith);
    }

    private boolean isArchive(FileObject fo) {
        String extension = fo.getName().getExtension();
        // No extension -> not an archive.
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        // Explicitly blocked? (e.g. "tmp", "res", "http")
        if (NON_ARCHIVE_SCHEMES.contains(extension)) {
            return false;
        }
        // Check VFS: Provider exists -> true
        return fo.getFileSystem().getFileSystemManager().hasProvider(extension);
    }
}
