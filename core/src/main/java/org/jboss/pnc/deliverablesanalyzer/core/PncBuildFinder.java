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
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncClient;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerArtifact;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PncBuildFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PncBuildFinder.class);

    private ManagedExecutor executor;

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
    }

    /**
     * Main Entry Point: Finds builds for a batch of checksums. Returns a map of BuildID -> PncBuild (containing the
     * identified artifacts).
     */
    public AnalyzerResult findBuilds(ConcurrentHashMap<QueueEntry, Collection<String>> checksumTable)
            throws ClientWebApplicationException {
        if (checksumTable == null || checksumTable.isEmpty()) {
            return AnalyzerResult.empty();
        }

        // Lookup Artifacts in PNC
        Set<AnalyzerArtifact> artifacts = lookupArtifactsInPnc(checksumTable);

        // Group Artifacts into Builds
        return groupArtifactsAsBuilds(artifacts);
    }

    private Set<AnalyzerArtifact> lookupArtifactsInPnc(ConcurrentHashMap<QueueEntry, Collection<String>> checksumTable)
            throws ClientWebApplicationException {
        Set<AnalyzerArtifact> artifacts = ConcurrentHashMap.newKeySet();

        List<CompletableFuture<Void>> tasks = checksumTable.entrySet()
                .stream()
                .map(
                        entry -> CompletableFuture.supplyAsync(() -> processChecksum(entry), executor)
                                .thenAccept(artifacts::add))
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

    private AnalyzerArtifact processChecksum(Map.Entry<QueueEntry, Collection<String>> entry) {
        QueueEntry queueEntry = entry.getKey();
        Checksum checksum = queueEntry.checksum();
        Collection<String> filenames = entry.getValue();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Parallel execution lookup using thread {} for checksum {}",
                    Thread.currentThread().getName(),
                    checksum);
        }

        try {
            return AnalyzerArtifact.fromPncArtifact(
                    findArtifactInPnc(checksum).orElse(null),
                    checksum,
                    filenames,
                    queueEntry.licenses(),
                    queueEntry.sourceUrl());
        } catch (ClientWebApplicationException e) {
            throw new CompletionException(e);
        }
    }

    private Optional<Artifact> findArtifactInPnc(Checksum checksum) throws ClientWebApplicationException {
        Collection<Artifact> artifacts;
        artifacts = lookupPncArtifactsByChecksum(checksum);
        if (artifacts == null || artifacts.isEmpty()) {
            return Optional.empty();
        }

        return getBestPncArtifact(artifacts);
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

    private Collection<Artifact> lookupPncArtifactsByChecksum(Checksum checksum) throws ClientWebApplicationException {
        return pncClient.getArtifactsBySha256(checksum.getSha256Value());
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

}
