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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncClient;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksumType;
import org.jboss.pnc.deliverablesanalyzer.model.finder.EnhancedArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.finder.PncBuild;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class PncBuildFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PncBuildFinder.class);

    private ManagedExecutor executor;

    private static final String BUILD_ID_ZERO = "0";

    private String emptyFileDigests;
    private String emptyZipDigests;

    @Inject
    BuildConfig buildConfig;

    @Inject
    PncClient pncClient;

    @PostConstruct
    void init() {
        this.executor = ManagedExecutor.builder()
                .maxAsync(buildConfig.pncNumThreads())
                .propagated(ThreadContext.CDI)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        initializeEmptyDigests();
    }

    /**
     * Main Entry Point: Finds builds for a batch of checksums. Returns a map of BuildID -> PncBuild (containing the
     * identified artifacts).
     */
    public Map<String, PncBuild> findBuilds(ConcurrentHashMap<QueueEntry, Collection<String>> checksumTable)
            throws ClientWebApplicationException {
        if (checksumTable == null || checksumTable.isEmpty()) {
            return Collections.emptyMap();
        }

        // Lookup Artifacts in PNC
        Set<EnhancedArtifact> artifacts = lookupArtifactsInPnc(checksumTable);

        // Group Artifacts into Builds
        ConcurrentHashMap<String, PncBuild> pncBuilds = groupArtifactsAsPncBuilds(artifacts);

        // Populate Metadata (Product Version, Push Report)
        populatePncBuildsMetadata(pncBuilds);

        return pncBuilds;
    }

    private Set<EnhancedArtifact> lookupArtifactsInPnc(ConcurrentHashMap<QueueEntry, Collection<String>> checksumTable)
            throws ClientWebApplicationException {
        Set<EnhancedArtifact> artifacts = ConcurrentHashMap.newKeySet();

        List<CompletableFuture<Void>> tasks = checksumTable.entrySet()
                .stream()
                .map(entry -> CompletableFuture.runAsync(() -> {
                    QueueEntry queueEntry = entry.getKey();
                    Checksum checksum = queueEntry.checksum();
                    Collection<String> fileNames = entry.getValue();

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Parallel execution lookup using thread {} for checksum {}",
                                Thread.currentThread().getName(),
                                checksum);
                    }

                    try {
                        EnhancedArtifact enhancedArtifact = new EnhancedArtifact(
                                findArtifactInPnc(checksum, fileNames).orElse(null),
                                checksum,
                                fileNames,
                                queueEntry.licenses(),
                                queueEntry.sourceUrl());
                        artifacts.add(enhancedArtifact);
                    } catch (ClientWebApplicationException e) {
                        throw new CompletionException(e);
                    }
                }, executor))
                .toList();

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ClientWebApplicationException) {
                throw (ClientWebApplicationException) e.getCause();
            }
            throw e;
        }

        return artifacts;
    }

    private Optional<Artifact> findArtifactInPnc(Checksum checksum, Collection<String> fileNames)
            throws ClientWebApplicationException {
        if (isEmptyFileDigest(checksum) || isEmptyZipDigest(checksum)) {
            LOGGER.warn("Skipped empty file/zip checksum {} for files: {}", checksum, String.join(", ", fileNames));
            return Optional.empty();
        }

        Collection<Artifact> artifacts;
        artifacts = lookupPncArtifactsByChecksum(checksum);
        if (artifacts == null || artifacts.isEmpty()) {
            return Optional.empty();
        }

        return getBestPncArtifact(artifacts);
    }

    private ConcurrentHashMap<String, PncBuild> groupArtifactsAsPncBuilds(Iterable<EnhancedArtifact> artifacts) {
        ConcurrentHashMap<String, PncBuild> pncBuilds = new ConcurrentHashMap<>();
        Build buildZero = Build.builder().id(BUILD_ID_ZERO).build();

        artifacts.forEach(artifact -> {
            Build build;
            if (artifact.getArtifact() != null && artifact.getArtifact().getBuild() != null) {
                build = artifact.getArtifact().getBuild();
            } else {
                build = buildZero;
            }

            pncBuilds.computeIfAbsent(build.getId(), k -> new PncBuild(build)).getBuiltArtifacts().add(artifact);
        });

        return pncBuilds;
    }

    private void populatePncBuildsMetadata(ConcurrentHashMap<String, PncBuild> pncBuilds)
            throws ClientWebApplicationException {
        List<CompletableFuture<Void>> tasks = pncBuilds.entrySet()
                .stream()
                .map(entry -> CompletableFuture.runAsync(() -> {
                    String buildId = entry.getKey();
                    PncBuild pncBuild = entry.getValue();

                    if (BUILD_ID_ZERO.equals(buildId)) {
                        return;
                    }

                    Build build = pncBuild.getBuild();
                    try {
                        if (build.getProductMilestone() != null) {
                            pncBuild.setProductVersion(
                                    pncClient.getProductVersion(build.getProductMilestone().getId()));
                        }
                        pncBuild.setBuildPushReport(pncClient.getBuildPushReport(build.getId()));
                    } catch (ClientWebApplicationException e) {
                        // If 404 - ignore if specific metadata is missing
                        if (e.getResponse().getStatus() != 404) {
                            throw new CompletionException(e);
                        }
                    }
                }, executor))
                .toList();

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ClientWebApplicationException) {
                throw (ClientWebApplicationException) e.getCause();
            }
            throw e;
        }
    }

    private Collection<Artifact> lookupPncArtifactsByChecksum(Checksum checksum) throws ClientWebApplicationException {
        return pncClient.getArtifactsBySha256(checksum.getValue());
    }

    private static Optional<Artifact> getBestPncArtifact(Collection<Artifact> artifacts) {
        if (artifacts.size() == 1) {
            return Optional.of(artifacts.iterator().next());
        }
        return artifacts.stream()
                .sorted(Comparator.comparing(PncBuildFinder::getArtifactQuality).reversed())
                .filter(a -> a.getBuild() != null)
                .findFirst()
                .or(() -> Optional.of(artifacts.iterator().next()));
    }

    // TODO: Use the new entity once Artifact DTO updated - ArtifactQuality
    private static int getArtifactQuality(Artifact a) {
        ArtifactQuality quality = a.getArtifactQuality();
        return switch (quality) {
            case NEW -> 1;
            case VERIFIED -> 2;
            case TESTED -> 3;
            case DEPRECATED -> -1;
            case BLACKLISTED -> -2;
            case TEMPORARY -> -3;
            case DELETED -> -4;
            default -> -100;
        };
    }

    private void initializeEmptyDigests() {
        // Calculate checksums for empty file (0 bytes)
        try {
            byte[] digest = DigestUtils.getDigest(ChecksumType.SHA256.getAlgorithm()).digest(new byte[0]);
            emptyFileDigests = Hex.encodeHexString(digest);
        } catch (Exception e) {
            LOGGER.warn("Failed to calculate empty file digest for {}", ChecksumType.SHA256, e);
        }

        // Calculate checksums for empty zip
        byte[] emptyZipBytes = createEmptyZipBytes();
        try {
            byte[] digest = DigestUtils.getDigest(ChecksumType.SHA256.getAlgorithm()).digest(emptyZipBytes);
            emptyZipDigests = Hex.encodeHexString(digest);
        } catch (Exception e) {
            LOGGER.warn("Failed to calculate empty zip digest for {}", ChecksumType.SHA256, e);
        }
    }

    private byte[] createEmptyZipBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            LOGGER.warn("Failed to generate empty zip bytes", e);
            return new byte[0];
        }
    }

    private boolean isEmptyFileDigest(Checksum checksum) {
        return checksum.getValue().equals(emptyFileDigests);
    }

    private boolean isEmptyZipDigest(Checksum checksum) {
        return checksum.getValue().equals(emptyZipDigests);
    }
}
