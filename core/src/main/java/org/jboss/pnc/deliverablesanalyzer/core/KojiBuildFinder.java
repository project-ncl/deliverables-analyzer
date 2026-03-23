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

import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveQuery;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildState;
import com.redhat.red.build.koji.model.xmlrpc.KojiIdOrName;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import io.quarkus.infinispan.client.Remote;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.pnc.deliverablesanalyzer.koji.KojiMultiCallClient;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.cache.KojiArchiveInfoWrapper;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.finder.KojiBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@ApplicationScoped
public class KojiBuildFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(KojiBuildFinder.class);

    private ManagedExecutor executor;

    @Inject
    BuildConfig buildConfig;

    @Inject
    KojiMultiCallClient kojiClient;

    @Inject
    @Remote("koji-archive-cache")
    RemoteCache<String, KojiArchiveInfoWrapper> archiveCache;

    @Inject
    @Remote("koji-build-cache")
    RemoteCache<Integer, KojiBuild> buildCache;

    @PostConstruct
    void init() {
        this.executor = ManagedExecutor.builder()
                .maxAsync(buildConfig.kojiNumThreads())
                .propagated(ThreadContext.CDI)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();
    }

    public AnalyzerResult findBuilds(ConcurrentHashMap<QueueEntry, Collection<String>> checksumTable) {
        if (checksumTable == null || checksumTable.isEmpty()) {
            return AnalyzerResult.empty();
        }

        // Lookup Artifacts in Koji (with SHA256)
        Set<AnalyzerArtifact> kojiArtifactsSha256 = lookupArtifactsInKoji(checksumTable, Checksum::getSha256Value);

        // Get Not Found Artifacts and Remove Them from Found Artifacts
        Set<AnalyzerArtifact> missingSha256 = new HashSet<>();
        Iterator<AnalyzerArtifact> iterator = kojiArtifactsSha256.iterator();
        while (iterator.hasNext()) {
            AnalyzerArtifact analyzerArtifact = iterator.next();
            if (analyzerArtifact.getBuildId() == null) {
                missingSha256.add(analyzerArtifact);
                iterator.remove();
            }
        }

        // Prepare for MD5 Lookup
        ConcurrentHashMap<QueueEntry, Collection<String>> md5ChecksumTable = new ConcurrentHashMap<>();
        for (AnalyzerArtifact missingArtifact : missingSha256) {
            md5ChecksumTable.put(
                    new QueueEntry(
                            missingArtifact.getInputPath(),
                            missingArtifact.getChecksum(),
                            missingArtifact.getLicenses()),
                    missingArtifact.getFilenames());
        }

        // Lookup Artifacts in Koji (with MD5)
        Set<AnalyzerArtifact> kojiArtifactsMd5 = lookupArtifactsInKoji(md5ChecksumTable, Checksum::getMd5Value);

        // Group Artifacts into Builds
        Set<AnalyzerArtifact> kojiArtifacts = new HashSet<>();
        kojiArtifacts.addAll(kojiArtifactsSha256);
        kojiArtifacts.addAll(kojiArtifactsMd5);

        return groupArtifactsAsBuilds(kojiArtifacts);
    }

    private Set<AnalyzerArtifact> lookupArtifactsInKoji(
            ConcurrentHashMap<QueueEntry, Collection<String>> checksumTable,
            Function<Checksum, String> hashExtractor) {
        Set<AnalyzerArtifact> artifacts = ConcurrentHashMap.newKeySet();

        List<QueueEntry> entries = new ArrayList<>(checksumTable.keySet());
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        // Split checksums to chunks for Koji processing
        int chunkSize = buildConfig.kojiMultiCallSize();
        List<List<QueueEntry>> chunks = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += chunkSize) {
            chunks.add(entries.subList(i, Math.min(i + chunkSize, entries.size())));
        }

        // Execute queries asynchronously
        for (List<QueueEntry> chunk : chunks) {
            tasks.add(
                    CompletableFuture
                            .supplyAsync(() -> processChecksumChunk(chunk, checksumTable, hashExtractor), executor)
                            .thenAccept(artifacts::addAll));
        }

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            LOGGER.error("Error occurred during parallel Koji lookups", e.getCause());
            throw e;
        }

        return artifacts;
    }

    private Set<AnalyzerArtifact> processChecksumChunk(
            List<QueueEntry> checksumChunk,
            Map<QueueEntry, Collection<String>> checksumTable,
            Function<Checksum, String> hashExtractor) {
        Set<AnalyzerArtifact> artifactsInChunk = new HashSet<>();
        Map<String, List<KojiArchiveInfo>> resolvedArchivesMap = new HashMap<>();
        List<KojiArchiveQuery> queriesToMake = new ArrayList<>();

        // Check Cached Archives
        for (QueueEntry entry : checksumChunk) {
            String hash = hashExtractor.apply(entry.checksum());
            KojiArchiveInfoWrapper cached = (archiveCache != null) ? archiveCache.get(hash) : null;

            if (cached != null && cached.data() != null) {
                // Cached
                resolvedArchivesMap.put(hash, cached.data());
            } else {
                // Uncached
                queriesToMake.add(new KojiArchiveQuery().withChecksum(hash));
            }
        }

        try {
            // Query Koji for Cache Misses
            if (!queriesToMake.isEmpty()) {
                List<List<KojiArchiveInfo>> kojiResults = kojiClient.listArchives(queriesToMake);

                Map<String, List<KojiArchiveInfo>> resultsByHash = IntStream.range(0, kojiResults.size())
                        .boxed()
                        .collect(Collectors.toMap(i -> queriesToMake.get(i).getChecksum(), kojiResults::get));

                // Enrich the Archives to populate the file extension/type names
                List<KojiArchiveInfo> archivesToEnrich = resultsByHash.values().stream().flatMap(List::stream).toList();

                if (!archivesToEnrich.isEmpty()) {
                    kojiClient.enrichArchiveTypeInfo(archivesToEnrich);
                }

                // Map results and Cache them asynchronously
                resultsByHash.forEach((hash, archiveInfos) -> {
                    resolvedArchivesMap.put(hash, archiveInfos);
                    if (archiveCache != null) {
                        archiveCache.putAsync(hash, new KojiArchiveInfoWrapper(archiveInfos));
                    }
                });
            }

            // Extract Build IDs and Fetch Details (for Best Build resolution)
            Set<Integer> uniqueBuildIds = resolvedArchivesMap.values()
                    .stream()
                    .flatMap(List::stream)
                    .map(KojiArchiveInfo::getBuildId)
                    .collect(Collectors.toSet());
            Map<Integer, KojiBuild> buildDetailsMap = uniqueBuildIds.isEmpty() ? Collections.emptyMap()
                    : fetchBuildDetails(uniqueBuildIds);

            // Resolve Best Build and Map to DTOs
            for (QueueEntry queueEntry : checksumChunk) {
                Checksum checksum = queueEntry.checksum();
                List<KojiArchiveInfo> archivesForChecksum = resolvedArchivesMap.get(hashExtractor.apply(checksum));
                Collection<String> filenames = checksumTable.get(queueEntry);

                KojiArchiveInfo bestArchive = findArtifactInKoji(archivesForChecksum, buildDetailsMap).orElse(null);
                KojiBuild buildDetails = bestArchive != null ? buildDetailsMap.get(bestArchive.getBuildId()) : null;

                AnalyzerArtifact analyzerArtifact = AnalyzerArtifact.fromKojiArchive(
                        bestArchive,
                        buildDetails,
                        queueEntry.checksum(),
                        filenames,
                        queueEntry.licenses(),
                        queueEntry.sourceUrl());
                artifactsInChunk.add(analyzerArtifact);
            }
        } catch (KojiClientException e) {
            throw new CompletionException("Koji XML-RPC archive query chunk failed", e);
        }

        return artifactsInChunk;
    }

    private Map<Integer, KojiBuild> fetchBuildDetails(Set<Integer> buildIds) throws KojiClientException {
        Map<Integer, KojiBuild> detailsMap = new HashMap<>();
        Set<Integer> missingIds = new HashSet<>();

        // Check Cache
        for (Integer id : buildIds) {
            KojiBuild cached = (buildCache != null) ? buildCache.get(id) : null;
            if (cached != null) {
                detailsMap.put(id, cached);
            } else {
                missingIds.add(id);
            }
        }

        // Fetch missing from Koji
        if (!missingIds.isEmpty()) {
            List<Integer> missingIdsList = new ArrayList<>(missingIds);
            List<KojiIdOrName> idsOrNames = missingIdsList.stream().map(KojiIdOrName::getFor).toList();
            List<KojiBuildInfo> buildInfos = kojiClient.getBuilds(idsOrNames);
            List<List<KojiTagInfo>> tagsList = kojiClient.listTagsByIds(missingIdsList);

            for (int i = 0; i < buildInfos.size(); i++) {
                KojiBuildInfo info = buildInfos.get(i);
                if (info != null) {
                    KojiBuild newDetails = new KojiBuild(info, tagsList.get(i));
                    detailsMap.put(info.getId(), newDetails);

                    if (buildCache != null) {
                        buildCache.putAsync(info.getId(), newDetails);
                    }
                }
            }
        }

        return detailsMap;
    }

    private Optional<KojiArchiveInfo> findArtifactInKoji(
            List<KojiArchiveInfo> archivesForChecksum,
            Map<Integer, KojiBuild> buildDetailsMap) {
        if (archivesForChecksum == null || archivesForChecksum.isEmpty()) {
            return Optional.empty();
        }

        KojiArchiveInfo bestArchive = findBestArchive(archivesForChecksum, buildDetailsMap);
        return Optional.of(bestArchive);
    }

    private KojiArchiveInfo findBestArchive(List<KojiArchiveInfo> archives, Map<Integer, KojiBuild> detailsMap) {
        if (archives.size() == 1)
            return archives.get(0);

        List<KojiArchiveInfo> sortedArchives = archives.stream()
                .sorted(Comparator.comparing(KojiArchiveInfo::getBuildId))
                .toList();

        List<KojiArchiveInfo> completeBuilds = new ArrayList<>();
        List<KojiArchiveInfo> completeTaggedBuilds = new ArrayList<>();
        List<KojiArchiveInfo> completeTaggedNonImportedBuilds = new ArrayList<>();

        for (KojiArchiveInfo archive : sortedArchives) {
            KojiBuild details = detailsMap.get(archive.getBuildId());
            if (details == null)
                continue;

            if (details.getInfo().getBuildState() == KojiBuildState.COMPLETE) {
                completeBuilds.add(archive);

                if (details.getTags() != null && !details.getTags().isEmpty()) {
                    completeTaggedBuilds.add(archive);

                    if (details.getInfo().getTaskId() != null) {
                        completeTaggedNonImportedBuilds.add(archive);
                    }
                }
            }
        }

        if (!completeTaggedNonImportedBuilds.isEmpty()) {
            return completeTaggedNonImportedBuilds.get(completeTaggedNonImportedBuilds.size() - 1);
        }
        if (!completeTaggedBuilds.isEmpty()) {
            return completeTaggedBuilds.get(completeTaggedBuilds.size() - 1);
        }
        if (!completeBuilds.isEmpty()) {
            return completeBuilds.get(completeBuilds.size() - 1);
        }

        return sortedArchives.get(sortedArchives.size() - 1);
    }

    private AnalyzerResult groupArtifactsAsBuilds(Iterable<AnalyzerArtifact> artifacts) {
        ConcurrentHashMap<String, AnalyzerBuild> kojiBuilds = new ConcurrentHashMap<>();
        Set<AnalyzerArtifact> notFoundArtifacts = ConcurrentHashMap.newKeySet();

        artifacts.forEach(artifact -> {
            if (artifact.getBuildId() != null) {
                kojiBuilds.computeIfAbsent(artifact.getBuildId(), k -> AnalyzerBuild.fromKojiBuild(artifact))
                        .getBuiltArtifacts()
                        .add(artifact);
            } else {
                notFoundArtifacts.add(artifact);
            }
        });

        return AnalyzerResult.of(kojiBuilds, notFoundArtifacts);
    }
}
