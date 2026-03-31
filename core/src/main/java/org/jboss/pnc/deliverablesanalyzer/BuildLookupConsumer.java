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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.koji.KojiBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifactMapper;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.core.ResultAggregator;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

@ApplicationScoped
public class BuildLookupConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildLookupConsumer.class);

    private static final int BATCH_SIZE = 500;

    private static final String EMPTY_FILE_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String EMPTY_ZIP_SHA256 = "8739c76e681f900923b900c9df0ef75cf421d39cabb54650c4b9ad19b6a76d85";

    @Inject
    PncBuildFinder pncBuildFinder;

    @Inject
    KojiBuildFinder kojiBuildFinder;

    @Inject
    ResultAggregator resultAggregator;

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
            throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "Error during build lookup", e);
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
            Map<QueueEntry, Collection<String>> batchForPath = createBatch(entry.getValue());

            // Filter out empty files
            Map<QueueEntry, Collection<String>> pncBatch = filterEmptyFiles(path, batchForPath, globalResults);
            if (pncBatch.isEmpty()) {
                continue;
            }

            // TODO Tomas: catch exceptions here so its clear if pnc or koji lookup failed
            // Query PNC
            Map<QueueEntry, Collection<String>> kojiBatch = processPncAndGetMissed(path, pncBatch, globalResults);

            // Query Koji
            if (!kojiBatch.isEmpty()) {
                processKojiAndHandleNotFound(path, kojiBatch, globalResults);
            }
        }
    }

    private Map<QueueEntry, Collection<String>> filterEmptyFiles(
            String path,
            Map<QueueEntry, Collection<String>> batchForPath,
            Map<String, AnalyzerResult> globalResults) {
        Map<QueueEntry, Collection<String>> validBatch = new HashMap<>();

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

    private Map<QueueEntry, Collection<String>> processPncAndGetMissed(
            String path,
            Map<QueueEntry, Collection<String>> pncBatch,
            Map<String, AnalyzerResult> globalResults) {
        AnalyzerResult pncResults = pncBuildFinder.findBuilds(pncBatch);

        Map<QueueEntry, Collection<String>> missedBatch = new HashMap<>();

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
            Map<QueueEntry, Collection<String>> kojiBatch,
            Map<String, AnalyzerResult> globalResults) {
        AnalyzerResult kojiResults = kojiBuildFinder.findBuilds(kojiBatch);

        // Whatever Koji missed is permanently not found
        AnalyzerResult pathResult = globalResults.get(path);
        pathResult.notFoundArtifacts().addAll(kojiResults.notFoundArtifacts());
        resultAggregator.mergeBatchResults(pathResult, kojiResults.foundBuilds());
    }

    private Map<QueueEntry, Collection<String>> createBatch(List<QueueEntry> queueEntries) {
        Map<QueueEntry, Collection<String>> batchMap = new HashMap<>();

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
        AnalyzerArtifact artifact = AnalyzerArtifactMapper.mapFromNotFound(
                queueEntry.checksum(),
                new ArrayList<>(filenames),
                queueEntry.licenses(),
                queueEntry.sourceUrl());
        globalResults.get(path).notFoundArtifacts().add(artifact);
    }

    private boolean isEmptyFileDigest(Checksum checksum) {
        return checksum.getSha256Value() != null && EMPTY_FILE_SHA256.equals(checksum.getSha256Value());
    }

    private boolean isEmptyZipDigest(Checksum checksum) {
        return checksum.getSha256Value() != null && EMPTY_ZIP_SHA256.equals(checksum.getSha256Value());
    }
}
