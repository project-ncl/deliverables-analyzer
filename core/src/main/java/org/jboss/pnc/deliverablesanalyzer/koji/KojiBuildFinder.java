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
import com.redhat.red.build.koji.model.xmlrpc.KojiNVRA;
import com.redhat.red.build.koji.model.xmlrpc.KojiRpmInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import io.quarkus.infinispan.client.Remote;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.pnc.deliverablesanalyzer.core.BuildConfig;
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

import java.nio.file.Paths;
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
    @Remote("koji-rpm-cache")
    RemoteCache<String, KojiRpmInfo> rpmCache;

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

        // Split Standard Archives from RPMs
        ConcurrentHashMap<QueueEntry, Collection<String>> rpmTable = new ConcurrentHashMap<>();
        ConcurrentHashMap<QueueEntry, Collection<String>> archiveTable = new ConcurrentHashMap<>();
        checksumTable.forEach((entry, filenames) -> {
            if (filenames.stream().anyMatch(f -> f.endsWith(".rpm"))) {
                rpmTable.put(entry, filenames);
            } else {
                archiveTable.put(entry, filenames);
            }
        });

        Set<AnalyzerArtifact> kojiArtifacts = new HashSet<>();

        // Process RPMs
        if (!rpmTable.isEmpty()) {
            // TODO Tomas: Test RPMs -> also, how to map them into result?!
            kojiArtifacts.addAll(lookupRpmsInKoji(rpmTable));
        }

        // Process Standard Archives
        if (!archiveTable.isEmpty()) {
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
            ConcurrentHashMap<QueueEntry, Collection<String>> md5ChecksumTable = new ConcurrentHashMap<>(
                    missingSha256.size());
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

            kojiArtifacts.addAll(kojiArtifactsSha256);
            kojiArtifacts.addAll(kojiArtifactsMd5);
        }

        // Group Artifacts into Builds
        return groupArtifactsAsBuilds(kojiArtifacts);
    }

    private Set<AnalyzerArtifact> lookupRpmsInKoji(ConcurrentHashMap<QueueEntry, Collection<String>> rpmTable) {
        Set<AnalyzerArtifact> artifacts = ConcurrentHashMap.newKeySet();

        List<QueueEntry> entries = new ArrayList<>(rpmTable.keySet());
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        // Split rpms to chunks for Koji processing
        int chunkSize = buildConfig.kojiMultiCallSize();
        for (int i = 0; i < entries.size(); i += chunkSize) {
            List<QueueEntry> chunk = entries.subList(i, Math.min(i + chunkSize, entries.size()));
            tasks.add(
                    CompletableFuture.supplyAsync(() -> processRpmChunk(chunk, rpmTable), executor)
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

    private Set<AnalyzerArtifact> lookupArtifactsInKoji(
            ConcurrentHashMap<QueueEntry, Collection<String>> checksumTable,
            Function<Checksum, String> hashExtractor) {
        Set<AnalyzerArtifact> artifacts = ConcurrentHashMap.newKeySet();

        // Split checksums to chunks for Koji processing
        List<QueueEntry> entries = new ArrayList<>(checksumTable.keySet());
        int chunkSize = buildConfig.kojiMultiCallSize();
        int capacity = (entries.size() / chunkSize) + 1;
        List<CompletableFuture<Void>> tasks = new ArrayList<>(capacity);

        for (int i = 0; i < entries.size(); i += chunkSize) {
            List<QueueEntry> chunk = entries.subList(i, Math.min(i + chunkSize, entries.size()));
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

    private Set<AnalyzerArtifact> processRpmChunk(
            List<QueueEntry> chunk,
            Map<QueueEntry, Collection<String>> rpmTable) {
        Map<QueueEntry, KojiRpmInfo> resolvedRpmsMap = new HashMap<>();
        List<KojiIdOrName> nvrasToQuery = new ArrayList<>();
        Map<KojiIdOrName, QueueEntry> queryToEntryMap = new HashMap<>();

        // Check Cached RPMs
        identifyRpmCacheHitsAndMisses(chunk, rpmTable, resolvedRpmsMap, nvrasToQuery, queryToEntryMap);

        try {
            // Query Koji for Cache Misses
            queryMissingRpmsFromKoji(nvrasToQuery, queryToEntryMap, resolvedRpmsMap);

            // Extract Build IDs and Fetch Details
            Set<Integer> uniqueBuildIds = resolvedRpmsMap.values()
                    .stream()
                    .map(KojiRpmInfo::getBuildId)
                    .collect(Collectors.toSet());
            Map<Integer, KojiBuild> buildDetailsMap = uniqueBuildIds.isEmpty() ? Collections.emptyMap()
                    : fetchBuildDetails(uniqueBuildIds);

            // Resolve and Map to DTOs
            return mapRpmsToAnalyzerArtifacts(chunk, rpmTable, resolvedRpmsMap, buildDetailsMap);
        } catch (KojiClientException e) {
            throw new CompletionException("Koji XML-RPC RPM query chunk failed", e);
        }
    }

    private Set<AnalyzerArtifact> processChecksumChunk(
            List<QueueEntry> chunk,
            Map<QueueEntry, Collection<String>> checksumTable,
            Function<Checksum, String> hashExtractor) {
        Map<String, List<KojiArchiveInfo>> resolvedArchivesMap = new HashMap<>();
        List<KojiArchiveQuery> checksumsToQuery = new ArrayList<>();

        // Check Cached Archives
        identifyArchiveCacheHitsAndMisses(chunk, hashExtractor, resolvedArchivesMap, checksumsToQuery);

        try {
            // Query Koji for Cache Misses
            queryMissingArchivesFromKoji(checksumsToQuery, resolvedArchivesMap);

            // Extract Build IDs and Fetch Details (for Best Build resolution)
            Set<Integer> uniqueBuildIds = resolvedArchivesMap.values()
                    .stream()
                    .flatMap(List::stream)
                    .map(KojiArchiveInfo::getBuildId)
                    .collect(Collectors.toSet());
            Map<Integer, KojiBuild> buildDetailsMap = uniqueBuildIds.isEmpty() ? Collections.emptyMap()
                    : fetchBuildDetails(uniqueBuildIds);

            // Resolve Best Build and Map to DTOs
            return mapArchivesToAnalyzerArtifacts(
                    chunk,
                    checksumTable,
                    hashExtractor,
                    resolvedArchivesMap,
                    buildDetailsMap);
        } catch (KojiClientException e) {
            throw new CompletionException("Koji XML-RPC archive query chunk failed", e);
        }
    }

    private void identifyRpmCacheHitsAndMisses(
            List<QueueEntry> chunk,
            Map<QueueEntry, Collection<String>> rpmTable,
            Map<QueueEntry, KojiRpmInfo> resolvedRpmsMap,
            List<KojiIdOrName> nvrasToQuery,
            Map<KojiIdOrName, QueueEntry> queryToEntryMap) {

        for (QueueEntry entry : chunk) {
            String hash = entry.checksum().getMd5Value();
            KojiRpmInfo cached = (rpmCache != null) ? rpmCache.get(hash) : null;

            if (cached != null) {
                // Cached
                resolvedRpmsMap.put(entry, cached);
            } else {
                // Uncached: Parse NVRA from filenames
                KojiIdOrName idOrName = parseNvrasForKojiQuery(entry, rpmTable);
                if (idOrName != null) {
                    nvrasToQuery.add(idOrName);
                    queryToEntryMap.put(idOrName, entry);
                }
            }
        }
    }

    private void identifyArchiveCacheHitsAndMisses(
            List<QueueEntry> chunk,
            Function<Checksum, String> hashExtractor,
            Map<String, List<KojiArchiveInfo>> resolvedArchivesMap,
            List<KojiArchiveQuery> checksumsToQuery) {
        for (QueueEntry entry : chunk) {
            String hash = hashExtractor.apply(entry.checksum());
            KojiArchiveInfoWrapper cached = (archiveCache != null) ? archiveCache.get(hash) : null;

            if (cached != null && cached.data() != null) {
                resolvedArchivesMap.put(hash, cached.data());
            } else {
                checksumsToQuery.add(new KojiArchiveQuery().withChecksum(hash));
            }
        }
    }

    private KojiIdOrName parseNvrasForKojiQuery(QueueEntry entry, Map<QueueEntry, Collection<String>> rpmTable) {
        Collection<String> filenames = rpmTable.get(entry);
        String rpmFilename = filenames.stream().filter(f -> f.endsWith(".rpm")).findFirst().orElse("");
        String cleanName = Paths.get(rpmFilename).getFileName().toString();

        try {
            KojiNVRA nvra = KojiNVRA.parseNVRA(cleanName);
            return KojiIdOrName
                    .getFor(nvra.getName() + "-" + nvra.getVersion() + "-" + nvra.getRelease() + "." + nvra.getArch());
        } catch (Exception e) {
            LOGGER.warn("Failed to parse NVRA from RPM filename: {}", cleanName);
            return null;
        }
    }

    private void queryMissingRpmsFromKoji(
            List<KojiIdOrName> nvrasToQuery,
            Map<KojiIdOrName, QueueEntry> queryToEntryMap,
            Map<QueueEntry, KojiRpmInfo> resolvedRpmsMap) throws KojiClientException {

        if (nvrasToQuery.isEmpty())
            return;

        List<KojiRpmInfo> kojiResults = kojiClient.getRPMs(nvrasToQuery);
        for (int i = 0; i < nvrasToQuery.size(); i++) {
            KojiRpmInfo rpm = kojiResults.get(i);
            QueueEntry entry = queryToEntryMap.get(nvrasToQuery.get(i));

            // Handle missing RPM or external RPMs (no build ID)
            if (rpm == null || rpm.getBuildId() == null) {
                continue;
            }

            String localMd5 = entry.checksum().getMd5Value();
            if (localMd5 != null && localMd5.equals(rpm.getPayloadhash())) {
                resolvedRpmsMap.put(entry, rpm);
                if (rpmCache != null) {
                    rpmCache.putAsync(localMd5, rpm);
                }
            } else {
                String errorMessage = "Mismatched payload hash for " + rpm.getName() + ": " + localMd5 + " != "
                        + rpm.getPayloadhash();
                LOGGER.error(errorMessage);
                throw new KojiClientException(errorMessage);
            }
        }
    }

    private void queryMissingArchivesFromKoji(
            List<KojiArchiveQuery> checksumsToQuery,
            Map<String, List<KojiArchiveInfo>> resolvedArchivesMap) throws KojiClientException {
        if (checksumsToQuery.isEmpty())
            return;

        List<List<KojiArchiveInfo>> kojiResults = kojiClient.listArchives(checksumsToQuery);

        List<KojiArchiveInfo> archivesToEnrich = kojiResults.stream().flatMap(List::stream).toList();
        if (!archivesToEnrich.isEmpty()) {
            kojiClient.enrichArchiveTypeInfo(archivesToEnrich);
        }

        for (int i = 0; i < checksumsToQuery.size(); i++) {
            String hash = checksumsToQuery.get(i).getChecksum();
            List<KojiArchiveInfo> archiveInfos = kojiResults.get(i);

            resolvedArchivesMap.put(hash, archiveInfos);
            if (archiveCache != null) {
                archiveCache.putAsync(hash, new KojiArchiveInfoWrapper(archiveInfos));
            }
        }
    }

    private Set<AnalyzerArtifact> mapRpmsToAnalyzerArtifacts(
            List<QueueEntry> chunk,
            Map<QueueEntry, Collection<String>> rpmTable,
            Map<QueueEntry, KojiRpmInfo> resolvedRpmsMap,
            Map<Integer, KojiBuild> buildDetailsMap) {
        Set<AnalyzerArtifact> artifactsInChunk = new HashSet<>();

        for (QueueEntry queueEntry : chunk) {
            KojiRpmInfo rpm = resolvedRpmsMap.get(queueEntry);
            Collection<String> filenames = rpmTable.get(queueEntry);

            if (rpm == null) {
                artifactsInChunk.add(
                        AnalyzerArtifactMapper.mapFromNotFound(
                                queueEntry.checksum(),
                                filenames,
                                queueEntry.licenses(),
                                queueEntry.sourceUrl()));
            } else {
                KojiBuild buildDetails = buildDetailsMap.get(rpm.getBuildId());
                AnalyzerArtifact analyzerArtifact = AnalyzerArtifactMapper.mapFromKojiRpm(
                        rpm,
                        buildDetails,
                        queueEntry.checksum(),
                        filenames,
                        queueEntry.licenses(),
                        queueEntry.sourceUrl());
                artifactsInChunk.add(analyzerArtifact);
            }
        }

        return artifactsInChunk;
    }

    private Set<AnalyzerArtifact> mapArchivesToAnalyzerArtifacts(
            List<QueueEntry> chunk,
            Map<QueueEntry, Collection<String>> checksumTable,
            Function<Checksum, String> hashExtractor,
            Map<String, List<KojiArchiveInfo>> resolvedArchivesMap,
            Map<Integer, KojiBuild> buildDetailsMap) {
        Set<AnalyzerArtifact> artifactsInChunk = new HashSet<>();

        for (QueueEntry queueEntry : chunk) {
            Checksum checksum = queueEntry.checksum();
            List<KojiArchiveInfo> archivesForChecksum = resolvedArchivesMap.get(hashExtractor.apply(checksum));
            Collection<String> filenames = checksumTable.get(queueEntry);

            KojiArchiveInfo bestArchive = findArtifactInKoji(archivesForChecksum, buildDetailsMap).orElse(null);

            if (bestArchive == null) {
                artifactsInChunk.add(
                        AnalyzerArtifactMapper
                                .mapFromNotFound(checksum, filenames, queueEntry.licenses(), queueEntry.sourceUrl()));
            } else {
                KojiBuild buildDetails = buildDetailsMap.get(bestArchive.getBuildId());
                AnalyzerArtifact analyzerArtifact = AnalyzerArtifactMapper.mapFromKojiArchive(
                        bestArchive,
                        buildDetails,
                        queueEntry.checksum(),
                        filenames,
                        queueEntry.licenses(),
                        queueEntry.sourceUrl());
                artifactsInChunk.add(analyzerArtifact);
            }
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

        if (!completeTaggedNonImportedBuilds.isEmpty()) {
            return completeTaggedNonImportedBuilds.getLast();
        }
        if (!completeTaggedBuilds.isEmpty()) {
            return completeTaggedBuilds.getLast();
        }
        if (!completeBuilds.isEmpty()) {
            return completeBuilds.getLast();
        }

        return sortedArchives.getLast();
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
