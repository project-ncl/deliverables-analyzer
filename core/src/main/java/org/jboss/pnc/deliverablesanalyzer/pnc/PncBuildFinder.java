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
package org.jboss.pnc.deliverablesanalyzer.pnc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.config.AnalyzerConfig;
import org.jboss.pnc.deliverablesanalyzer.core.ScannedArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifactMapper;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksummedFile;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.virtual.threads.VirtualThreads;

@ApplicationScoped
public class PncBuildFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PncBuildFinder.class);

    @Inject
    AnalyzerConfig analyzerConfig;

    @Inject
    PncClient pncClient;

    @Inject
    @VirtualThreads
    ExecutorService executorService;

    /**
     * Maximum number of sha256 checksums bundled into a single PNC query. Each checksum contributes ~64 characters
     * (plus
     * encoding) to the {@code sha256=in=(...)} filter, so this is kept conservative to stay well within HTTP
     * request-line
     * limits of any proxy/gateway in front of PNC, while still collapsing many per-checksum lookups into one request.
     */
    private static final int SHA256_BATCH_SIZE = 50;

    private Semaphore pncThrottle;

    @PostConstruct
    void init() {
        this.pncThrottle = new Semaphore(analyzerConfig.pncNumThreads(), true);
    }

    /**
     * Main Entry Point: Finds builds for a batch of checksums. Returns a map of BuildID -> PncBuild (containing the
     * identified artifacts).
     */
    public AnalyzerResult findBuilds(Map<ScannedArtifact, Collection<String>> checksumTable) {
        if (checksumTable == null || checksumTable.isEmpty()) {
            return AnalyzerResult.empty();
        }

        // Lookup Artifacts in PNC
        Set<AnalyzerArtifact> artifacts = lookupArtifactsInPnc(checksumTable);

        // Group Artifacts into Builds
        return groupArtifactsAsBuilds(artifacts);
    }

    private Set<AnalyzerArtifact> lookupArtifactsInPnc(Map<ScannedArtifact, Collection<String>> checksumTable) {
        // Collect the distinct sha256 checksums we need to look up in PNC.
        Set<String> sha256s = checksumTable.keySet()
                .stream()
                .map(scannedArtifact -> scannedArtifact.file().getSha256Value())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Query PNC in batches, obtaining the best matching artifact per sha256.
        Map<String, Artifact> pncArtifactsBySha256 = fetchBestArtifactsBySha256(sha256s);

        // Map every scanned artifact to an AnalyzerArtifact using the data already fetched (no further PNC calls).
        Set<AnalyzerArtifact> artifacts = ConcurrentHashMap.newKeySet();
        checksumTable.forEach((scannedArtifact, filenames) -> {
            ChecksummedFile checksummedFile = scannedArtifact.file();
            String sha256 = checksummedFile.getSha256Value();
            Artifact pncArtifact = sha256 == null ? null : pncArtifactsBySha256.get(sha256);

            artifacts.add(
                    AnalyzerArtifactMapper.mapFromPnc(
                            pncArtifact,
                            checksummedFile,
                            filenames,
                            scannedArtifact.licenses(),
                            scannedArtifact.sourceUrl()));
        });

        return artifacts;
    }

    /**
     * Looks up the given sha256 checksums in PNC, bundling them into batched queries that run in parallel, and returns
     * the best matching artifact for each sha256 that was found.
     */
    private Map<String, Artifact> fetchBestArtifactsBySha256(Set<String> sha256s) {
        if (sha256s.isEmpty()) {
            return Map.of();
        }

        List<List<String>> batches = partition(sha256s, SHA256_BATCH_SIZE);
        Map<String, List<Artifact>> artifactsBySha256 = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> tasks = batches.stream()
                .map(batch -> CompletableFuture.runAsync(() -> {
                    try {
                        pncThrottle.acquire();
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Batched PNC lookup for {} checksums", batch.size());
                        }
                        for (Artifact artifact : pncClient.getArtifactsBySha256(batch)) {
                            artifactsBySha256
                                    .computeIfAbsent(artifact.getSha256(), k -> new ArrayList<>())
                                    .add(artifact);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(e);
                    } finally {
                        pncThrottle.release();
                    }
                }, executorService))
                .toList();

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();

            if (cause instanceof CancellationException ce) {
                throw ce;
            }

            if (cause instanceof ClientWebApplicationException cwae) {
                throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "PNC REST API call failed", cwae);
            }

            throw new ReasonedException(
                    ResultStatus.SYSTEM_ERROR,
                    "Unexpected error during PNC lookup",
                    cause != null ? cause : e);
        }

        Map<String, Artifact> bestArtifacts = new ConcurrentHashMap<>();
        artifactsBySha256
                .forEach(
                        (sha256, candidates) -> getBestPncArtifact(candidates)
                                .ifPresent(a -> bestArtifacts.put(sha256, a)));
        return bestArtifacts;
    }

    private static <T> List<List<T>> partition(Collection<T> items, int batchSize) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < all.size(); i += batchSize) {
            batches.add(all.subList(i, Math.min(i + batchSize, all.size())));
        }
        return batches;
    }

    private AnalyzerResult groupArtifactsAsBuilds(Iterable<AnalyzerArtifact> artifacts) {
        ConcurrentHashMap<String, AnalyzerBuild> pncBuilds = new ConcurrentHashMap<>();
        Set<AnalyzerArtifact> notFoundArtifacts = ConcurrentHashMap.newKeySet();

        artifacts.forEach(artifact -> {
            if (artifact.getBuildId() != null) {
                pncBuilds.computeIfAbsent(artifact.getBuildId(), k -> AnalyzerBuild.fromPncBuild(artifact))
                        .getBuiltArtifacts()
                        .add(artifact);
            } else {
                notFoundArtifacts.add(artifact);
            }
        });

        return AnalyzerResult.of(pncBuilds, notFoundArtifacts);
    }

    private static Optional<Artifact> getBestPncArtifact(Collection<Artifact> artifacts) {
        if (artifacts.size() == 1) {
            return Optional.of(artifacts.iterator().next());
        }
        return artifacts.stream()
                .filter(a -> a.getBuild() != null)
                .max(Comparator.comparingInt(PncBuildFinder::getArtifactQuality))
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
            case IMPORTED -> -5;
        };
    }

}
