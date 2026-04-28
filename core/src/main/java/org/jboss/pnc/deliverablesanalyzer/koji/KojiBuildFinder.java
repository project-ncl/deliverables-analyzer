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
package org.jboss.pnc.deliverablesanalyzer.koji;

import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveQuery;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildState;
import com.redhat.red.build.koji.model.xmlrpc.KojiIdOrName;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import io.quarkus.infinispan.client.Remote;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.config.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifactMapper;
import org.jboss.pnc.deliverablesanalyzer.model.cache.KojiArchiveInfoWrapper;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.finder.KojiBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class KojiBuildFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(KojiBuildFinder.class);

    @Inject
    BuildConfig buildConfig;

    @Inject
    KojiMultiCallClient kojiClient;

    @Inject
    @Remote("koji-archives")
    RemoteCache<String, KojiArchiveInfoWrapper> archiveCache;

    @Inject
    @Remote("koji-builds")
    RemoteCache<Integer, KojiBuild> buildCache;

    @Inject
    @VirtualThreads
    ExecutorService executorService;

    private Semaphore kojiThrottle;

    @PostConstruct
    void init() {
        if (buildConfig.disableCache()) {
            LOGGER.info("Koji Caches disabled via configuration");
            this.archiveCache = null;
            this.buildCache = null;
        }

        this.kojiThrottle = new Semaphore(buildConfig.kojiNumThreads());
    }

    public AnalyzerResult findBuilds(Map<QueueEntry, Collection<String>> checksumTable) {
        if (checksumTable == null || checksumTable.isEmpty()) {
            return AnalyzerResult.empty();
        }

        // Lookup Artifacts in Koji (SHA256 with MD5 fallback)
        Set<AnalyzerArtifact> kojiArtifacts = lookupArtifactsInKoji(checksumTable);

        // Group Artifacts into Builds
        return groupArtifactsAsBuilds(kojiArtifacts);
    }

    private Set<AnalyzerArtifact> lookupArtifactsInKoji(Map<QueueEntry, Collection<String>> checksumTable) {
        Set<AnalyzerArtifact> artifacts = ConcurrentHashMap.newKeySet();
        Map<QueueEntry, Collection<String>> unresolvedBatch = new ConcurrentHashMap<>(checksumTable);

        // SHA256 Lookup
        Map<QueueEntry, Collection<String>> sha256Batch = unresolvedBatch.entrySet()
                .stream()
                .filter(e -> e.getKey().checksum().getSha256Value() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!sha256Batch.isEmpty()) {
            Set<AnalyzerArtifact> sha256Found = executeParallelLookup(
                    sha256Batch,
                    checksumTable,
                    Checksum::getSha256Value);
            artifacts.addAll(sha256Found);

            if (!sha256Found.isEmpty()) {
                Set<String> foundHashes = sha256Found.stream()
                        .map(a -> a.getChecksum().getSha256Value())
                        .collect(Collectors.toSet());

                unresolvedBatch.keySet().removeIf(entry -> foundHashes.contains(entry.checksum().getSha256Value()));
            }
        }

        // MD5 Lookup
        Map<QueueEntry, Collection<String>> md5Batch = unresolvedBatch.entrySet()
                .stream()
                .filter(e -> e.getKey().checksum().getMd5Value() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!md5Batch.isEmpty()) {
            Set<AnalyzerArtifact> md5Found = executeParallelLookup(md5Batch, checksumTable, Checksum::getMd5Value);
            artifacts.addAll(md5Found);

            if (!md5Found.isEmpty()) {
                Set<String> foundHashes = md5Found.stream()
                        .map(a -> a.getChecksum().getMd5Value())
                        .collect(Collectors.toSet());

                unresolvedBatch.keySet().removeIf(entry -> foundHashes.contains(entry.checksum().getMd5Value()));
            }
        }

        // Map remaining as not found
        for (QueueEntry notFound : unresolvedBatch.keySet()) {
            // Cache empty state so future runs skip the lookup
            if (archiveCache != null && notFound.checksum().getSha256Value() != null) {
                archiveCache.putAsync(
                    notFound.checksum().getSha256Value(),
                    new KojiArchiveInfoWrapper(Collections.emptyList()));
            }

            artifacts.add(
                    AnalyzerArtifactMapper.mapFromNotFound(
                            notFound.checksum(),
                            checksumTable.get(notFound),
                            notFound.licenses(),
                            notFound.sourceUrl()));
        }

        return artifacts;
    }

    private Set<AnalyzerArtifact> executeParallelLookup(
            Map<QueueEntry, Collection<String>> validForHash,
            Map<QueueEntry, Collection<String>> originalChecksumTable,
            Function<Checksum, String> hashExtractor) {
        Set<AnalyzerArtifact> artifacts = ConcurrentHashMap.newKeySet();
        List<QueueEntry> entries = new ArrayList<>(validForHash.keySet());

        int chunkSize = buildConfig.kojiMultiCallSize();
        int capacity = (entries.size() / chunkSize) + 1;
        List<CompletableFuture<Void>> tasks = new ArrayList<>(capacity);

        for (int i = 0; i < entries.size(); i += chunkSize) {
            List<QueueEntry> chunk = entries.subList(i, Math.min(i + chunkSize, entries.size()));

            tasks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    kojiThrottle.acquire();
                    return processChecksumChunk(chunk, originalChecksumTable, hashExtractor);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                } finally {
                    kojiThrottle.release();
                }
            }, executorService).thenAccept(artifacts::addAll));
        }

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException ce) {
                throw ce;
            }
            LOGGER.error("Error occurred during parallel Koji lookups", cause);
            throw new ReasonedException(
                    ResultStatus.SYSTEM_ERROR,
                    "Koji XML-RPC API call failed",
                    cause != null ? cause : e);
        }

        return artifacts;
    }

    private Set<AnalyzerArtifact> processChecksumChunk(
            List<QueueEntry> chunk,
            Map<QueueEntry, Collection<String>> checksumTable,
            Function<Checksum, String> hashExtractor) {
        Map<String, List<KojiArchiveInfo>> resolvedArchivesMap = new HashMap<>();
        List<KojiArchiveQuery> checksumsToQuery = new ArrayList<>();

        // Identify Cache Hits and Misses
        identifyArchiveCacheHitsAndMisses(chunk, hashExtractor, resolvedArchivesMap, checksumsToQuery);

        // Query Koji
        try {
            queryMissingArchivesFromKoji(chunk, checksumsToQuery, resolvedArchivesMap, hashExtractor);
        } catch (KojiClientException e) {
            throw new CompletionException("Koji XML-RPC archive query chunk failed", e);
        }

        // Extract Build IDs and Fetch Details
        Set<Integer> uniqueBuildIds = resolvedArchivesMap.values()
                .stream()
                .flatMap(List::stream)
                .map(KojiArchiveInfo::getBuildId)
                .collect(Collectors.toSet());

        Map<Integer, KojiBuild> buildDetailsMap;
        try {
            buildDetailsMap = uniqueBuildIds.isEmpty() ? Collections.emptyMap() : fetchBuildDetails(uniqueBuildIds);
        } catch (KojiClientException e) {
            throw new CompletionException("Failed to fetch Koji build details", e);
        }

        // Map the found artifacts
        return mapArchivesToAnalyzerArtifacts(
                chunk,
                checksumTable,
                hashExtractor,
                resolvedArchivesMap,
                buildDetailsMap);
    }

    private void identifyArchiveCacheHitsAndMisses(
            List<QueueEntry> chunk,
            Function<Checksum, String> hashExtractor,
            Map<String, List<KojiArchiveInfo>> resolvedArchivesMap,
            List<KojiArchiveQuery> checksumsToQuery) {

        for (QueueEntry entry : chunk) {
            String queryHash = hashExtractor.apply(entry.checksum());
            String cacheKey = entry.checksum().getSha256Value();

            // Strict Null Check to prevent Infinispan crashes on MD5 fallbacks
            KojiArchiveInfoWrapper cached = (archiveCache != null && cacheKey != null) ? archiveCache.get(cacheKey)
                    : null;

            if (cached != null && cached.data() != null) {
                resolvedArchivesMap.put(queryHash, cached.data());
            } else {
                checksumsToQuery.add(new KojiArchiveQuery().withChecksum(queryHash));
            }
        }
    }

    private void queryMissingArchivesFromKoji(
            List<QueueEntry> chunk,
            List<KojiArchiveQuery> checksumsToQuery,
            Map<String, List<KojiArchiveInfo>> resolvedArchivesMap,
            Function<Checksum, String> hashExtractor) throws KojiClientException {

        if (checksumsToQuery.isEmpty())
            return;

        List<List<KojiArchiveInfo>> kojiResults = kojiClient.listArchives(checksumsToQuery);

        List<KojiArchiveInfo> archivesToEnrich = kojiResults.stream().flatMap(List::stream).toList();
        if (!archivesToEnrich.isEmpty()) {
            kojiClient.enrichArchiveTypeInfo(archivesToEnrich);
        }

        for (int i = 0; i < checksumsToQuery.size(); i++) {
            String queryHash = checksumsToQuery.get(i).getChecksum();
            List<KojiArchiveInfo> archiveInfos = kojiResults.get(i);

            resolvedArchivesMap.put(queryHash, archiveInfos);

            if (archiveCache != null && !archiveInfos.isEmpty()) {
                QueueEntry originalEntry = chunk.stream()
                        .filter(e -> queryHash.equals(hashExtractor.apply(e.checksum())))
                        .findFirst()
                        .orElse(null);

                // Write to cache using SHA256
                if (originalEntry != null && originalEntry.checksum().getSha256Value() != null) {
                    archiveCache.putAsync(
                            originalEntry.checksum().getSha256Value(),
                            new KojiArchiveInfoWrapper(archiveInfos));
                }
            }
        }
    }

    private Set<AnalyzerArtifact> mapArchivesToAnalyzerArtifacts(
            List<QueueEntry> chunk,
            Map<QueueEntry, Collection<String>> checksumTable,
            Function<Checksum, String> hashExtractor,
            Map<String, List<KojiArchiveInfo>> resolvedArchivesMap,
            Map<Integer, KojiBuild> buildDetailsMap) {

        Set<AnalyzerArtifact> artifactsInPass = new HashSet<>();

        for (QueueEntry queueEntry : chunk) {
            Checksum checksum = queueEntry.checksum();
            List<KojiArchiveInfo> archivesForChecksum = resolvedArchivesMap.get(hashExtractor.apply(checksum));
            Collection<String> filenames = checksumTable.get(queueEntry);

            KojiArchiveInfo bestArchive = findArtifactInKoji(archivesForChecksum, buildDetailsMap).orElse(null);

            if (bestArchive != null) {
                KojiBuild buildDetails = buildDetailsMap.get(bestArchive.getBuildId());
                AnalyzerArtifact analyzerArtifact = AnalyzerArtifactMapper.mapFromKojiArchive(
                        bestArchive,
                        buildDetails,
                        queueEntry.checksum(),
                        filenames,
                        queueEntry.licenses(),
                        queueEntry.sourceUrl());
                artifactsInPass.add(analyzerArtifact);
            }
        }

        return artifactsInPass;
    }

    private Map<Integer, KojiBuild> fetchBuildDetails(Set<Integer> buildIds) throws KojiClientException {
        Map<Integer, KojiBuild> detailsMap = new HashMap<>();
        Set<Integer> missingIds = new HashSet<>();

        for (Integer id : buildIds) {
            KojiBuild cached = (buildCache != null) ? buildCache.get(id) : null;
            if (cached != null) {
                detailsMap.put(id, cached);
            } else {
                missingIds.add(id);
            }
        }

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

        return Optional.of(findBestArchive(archivesForChecksum, buildDetailsMap));
    }

    private KojiArchiveInfo findBestArchive(List<KojiArchiveInfo> archives, Map<Integer, KojiBuild> detailsMap) {
        if (archives.size() == 1)
            return archives.getFirst();

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

        if (!completeTaggedNonImportedBuilds.isEmpty())
            return completeTaggedNonImportedBuilds.getLast();
        if (!completeTaggedBuilds.isEmpty())
            return completeTaggedBuilds.getLast();
        if (!completeBuilds.isEmpty())
            return completeBuilds.getLast();

        return sortedArchives.getLast();
    }

    private AnalyzerResult groupArtifactsAsBuilds(Iterable<AnalyzerArtifact> artifacts) {
        Map<String, AnalyzerBuild> kojiBuilds = new HashMap<>();
        Set<AnalyzerArtifact> notFoundArtifacts = new HashSet<>();

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
