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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.core.KojiBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.core.PncBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.core.ResultAggregator;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksumType;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class BuildLookupConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildLookupConsumer.class);

    private static final int BATCH_SIZE = 500;

    private String emptyFileDigests;
    private String emptyZipDigests;

    @Inject
    PncBuildFinder pncBuildFinder;

    @Inject
    KojiBuildFinder kojiBuildFinder;

    @Inject
    ResultAggregator resultAggregator;

    @PostConstruct
    void init() {
        initializeEmptyDigests();
    }

    public void consume(BlockingQueue<QueueEntry> queue, Map<String, AnalyzerResult> results) {
        LOGGER.info("Consumer started. Waiting for checksums...");

        List<QueueEntry> queueBatch = new ArrayList<>(BATCH_SIZE);

        try {
            while (true) {
                QueueEntry queueEntry = queue.take();
                queueBatch.add(queueEntry);
                queue.drainTo(queueBatch, BATCH_SIZE - 1);

                int pillIndex = queueBatch.indexOf(QueueEntry.POISON_PILL);
                if (pillIndex != -1) {
                    List<QueueEntry> validItems = queueBatch.subList(0, pillIndex);
                    processBatch(validItems, results);
                    break;
                }

                processBatch(queueBatch, results);
                queueBatch.clear();
            }

            resultAggregator.cleanUp(results);
        } catch (InterruptedException e) {
            LOGGER.warn("Consumer interrupted", e);
            Thread.currentThread().interrupt();
            throw new CancellationException("Consumer interrupted");
        } catch (Exception e) {
            LOGGER.error("Consumer failed", e);
            throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "Error during PNC build lookup", e);
        }

        LOGGER.info("Consumer finished.");
    }

    private void processBatch(List<QueueEntry> batch, Map<String, AnalyzerResult> globalResults) {
        if (batch.isEmpty()) {
            return;
        }

        LOGGER.debug("Processing batch of {} checksums", batch.size());

        var batchByPath = batch.stream().collect(Collectors.groupingBy(QueueEntry::sourceUrl));
        for (var entry : batchByPath.entrySet()) {
            String path = entry.getKey();
            ConcurrentHashMap<QueueEntry, Collection<String>> batchForPath = createBatch(entry.getValue());

            // Filter out empty files
            ConcurrentHashMap<QueueEntry, Collection<String>> pncBatch = filterEmptyFiles(
                    path,
                    batchForPath,
                    globalResults);
            if (pncBatch.isEmpty()) {
                continue;
            }

            // Query PNC
            ConcurrentHashMap<QueueEntry, Collection<String>> kojiBatch = processPncAndGetMissed(
                    path,
                    pncBatch,
                    globalResults);

            // Query Koji
            if (!kojiBatch.isEmpty()) {
                processKojiAndHandleNotFound(path, kojiBatch, globalResults);
            }
        }
    }

    private ConcurrentHashMap<QueueEntry, Collection<String>> filterEmptyFiles(
            String path,
            ConcurrentHashMap<QueueEntry, Collection<String>> batchForPath,
            Map<String, AnalyzerResult> globalResults) {
        ConcurrentHashMap<QueueEntry, Collection<String>> validBatch = new ConcurrentHashMap<>();

        for (Map.Entry<QueueEntry, Collection<String>> batchEntry : batchForPath.entrySet()) {
            QueueEntry queueEntry = batchEntry.getKey();
            Collection<String> filenames = batchEntry.getValue();

            if (isEmptyFileDigest(queueEntry.checksum()) || isEmptyZipDigest(queueEntry.checksum())) {
                LOGGER.debug("Short-circuiting empty file/zip to Build Zero: {}", filenames);
                mapToNotFound(path, queueEntry, filenames, globalResults);
            } else {
                validBatch.put(queueEntry, filenames);
            }
        }

        return validBatch;
    }

    private ConcurrentHashMap<QueueEntry, Collection<String>> processPncAndGetMissed(
            String path,
            ConcurrentHashMap<QueueEntry, Collection<String>> pncBatch,
            Map<String, AnalyzerResult> globalResults) {
        AnalyzerResult pncResults = pncBuildFinder.findBuilds(pncBatch);

        ConcurrentHashMap<QueueEntry, Collection<String>> missedBatch = new ConcurrentHashMap<>();

        Set<AnalyzerArtifact> missingArtifacts = pncResults.notFoundArtifacts();
        if (!missingArtifacts.isEmpty()) {
            Set<String> missingChecksums = missingArtifacts.stream()
                    .map(a -> a.getChecksum().getSha256Value())
                    .collect(Collectors.toSet());

            for (Map.Entry<QueueEntry, Collection<String>> entry : pncBatch.entrySet()) {
                if (missingChecksums.contains(entry.getKey().checksum().getSha256Value())) {
                    missedBatch.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (!pncResults.foundBuilds().isEmpty()) {
            resultAggregator.mergeBatchResults(globalResults.get(path), pncResults.foundBuilds());
        }

        return missedBatch;
    }

    private void processKojiAndHandleNotFound(
            String path,
            ConcurrentHashMap<QueueEntry, Collection<String>> kojiBatch,
            Map<String, AnalyzerResult> globalResults) {
        AnalyzerResult kojiResults = kojiBuildFinder.findBuilds(kojiBatch);

        // Whatever Koji missed is permanently not found
        AnalyzerResult pathResult = globalResults.get(path);
        pathResult.notFoundArtifacts().addAll(kojiResults.notFoundArtifacts());
        resultAggregator.mergeBatchResults(pathResult, kojiResults.foundBuilds());
    }

    private ConcurrentHashMap<QueueEntry, Collection<String>> createBatch(List<QueueEntry> queueEntries) {
        ConcurrentHashMap<QueueEntry, Collection<String>> batchMap = new ConcurrentHashMap<>();

        Map<String, QueueEntry> checksumEntries = new HashMap<>();
        for (QueueEntry queueEntry : queueEntries) {
            String hash = queueEntry.checksum().getSha256Value();

            QueueEntry keyEntry = checksumEntries.computeIfAbsent(hash, k -> queueEntry);
            batchMap.computeIfAbsent(keyEntry, k -> ConcurrentHashMap.newKeySet())
                    .add(queueEntry.checksum().getFilename());
        }

        return batchMap;
    }

    private void mapToNotFound(
            String path,
            QueueEntry queueEntry,
            Collection<String> filenames,
            Map<String, AnalyzerResult> globalResults) {
        AnalyzerArtifact artifact = AnalyzerArtifact.fromNotFoundArtifact(
                queueEntry.checksum(),
                new ArrayList<>(filenames),
                queueEntry.licenses(),
                queueEntry.sourceUrl());
        globalResults.get(path).notFoundArtifacts().add(artifact);
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
        return checksum.getSha256Value().equals(emptyFileDigests);
    }

    private boolean isEmptyZipDigest(Checksum checksum) {
        return checksum.getSha256Value().equals(emptyZipDigests);
    }
}
