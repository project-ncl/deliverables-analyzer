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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.core.ResultAggregator;
import org.jboss.pnc.deliverablesanalyzer.koji.KojiBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifactMapper;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncBuildFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        }

        LOGGER.info("Consumer finished.");
    }

    private void processBatch(List<QueueEntry> batch, Map<String, AnalyzerResult> globalResults) {
        if (batch.isEmpty())
            return;

        LOGGER.debug("Processing batch of {} checksums", batch.size());

        var batchByPath = batch.stream().collect(Collectors.groupingBy(QueueEntry::sourceUrl));
        for (var entry : batchByPath.entrySet()) {
            String path = entry.getKey();

            // Create the initial batch of everything to be identified
            Map<QueueEntry, Collection<String>> unresolvedBatch = createBatch(entry.getValue());

            // Remove empty files/zips from the batch
            filterEmptyFiles(path, unresolvedBatch, globalResults);
            if (unresolvedBatch.isEmpty()) {
                continue;
            }

            // PNC Lookup
            try {
                processLookup(path, unresolvedBatch, globalResults, pncBuildFinder::findBuilds);
            } catch (Exception e) {
                if (e instanceof CancellationException || e instanceof ReasonedException)
                    throw e;
                throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "PNC Lookup failed for path: " + path, e);
            }

            // Koji Lookup
            if (!unresolvedBatch.isEmpty()) {
                try {
                    processLookup(path, unresolvedBatch, globalResults, kojiBuildFinder::findBuilds);
                } catch (Exception e) {
                    if (e instanceof CancellationException || e instanceof ReasonedException)
                        throw e;
                    throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "Koji Lookup failed for path: " + path, e);
                }
            }

            // Map unresolved items to not found artifacts
            if (!unresolvedBatch.isEmpty()) {
                LOGGER.debug("Marking {} unresolved artifacts as Not Found", unresolvedBatch.size());

                for (Map.Entry<QueueEntry, Collection<String>> unresolvedEntry : unresolvedBatch.entrySet()) {
                    mapToNotFound(path, unresolvedEntry.getKey(), unresolvedEntry.getValue(), globalResults);
                }

                unresolvedBatch.clear();
            }
        }
    }

    private void filterEmptyFiles(
            String path,
            Map<QueueEntry, Collection<String>> unresolvedBatch,
            Map<String, AnalyzerResult> globalResults) {
        var iterator = unresolvedBatch.entrySet().iterator();
        while (iterator.hasNext()) {
            var batchEntry = iterator.next();
            QueueEntry queueEntry = batchEntry.getKey();
            Collection<String> filenames = batchEntry.getValue();

            if (isEmptyFileDigest(queueEntry.checksum()) || isEmptyZipDigest(queueEntry.checksum())) {
                LOGGER.debug("Short-circuiting empty file/zip to Build Zero: {}", filenames);
                mapToNotFound(path, queueEntry, filenames, globalResults);

                // Remote the resolved entry from the batch
                iterator.remove();
            }
        }
    }

    private void processLookup(
            String path,
            Map<QueueEntry, Collection<String>> unresolvedBatch,
            Map<String, AnalyzerResult> globalResults,
            Function<Map<QueueEntry, Collection<String>>, AnalyzerResult> findFunction) {
        AnalyzerResult lookupResults = findFunction.apply(unresolvedBatch);

        if (lookupResults == null || lookupResults.foundBuilds().isEmpty()) {
            // Provider found nothing, batch stays unchanged
            return;
        }

        // Merge found builds into the final result
        resultAggregator.mergeBatchResults(globalResults.get(path), lookupResults.foundBuilds());

        // Remove found items from the unresolved batch
        Set<String> missingChecksums = lookupResults.notFoundArtifacts()
                .stream()
                .map(a -> a.getChecksum().getSha256Value())
                .collect(Collectors.toSet());
        unresolvedBatch.keySet().removeIf(entry -> !missingChecksums.contains(entry.checksum().getSha256Value()));
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
