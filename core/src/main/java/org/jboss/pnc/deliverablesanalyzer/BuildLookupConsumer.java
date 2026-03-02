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
import org.jboss.pnc.deliverablesanalyzer.core.PncBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.core.ResultAggregator;
import org.jboss.pnc.deliverablesanalyzer.model.finder.PncBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class BuildLookupConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildLookupConsumer.class);

    private static final int BATCH_SIZE = 500;

    @Inject
    PncBuildFinder pncBuildFinder;

    @Inject
    ResultAggregator resultAggregator;

    public void consume(BlockingQueue<QueueEntry> queue, Map<String, Map<String, PncBuild>> results) {
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

    private void processBatch(List<QueueEntry> batch, Map<String, Map<String, PncBuild>> globalResults) {
        if (batch.isEmpty()) {
            return;
        }

        LOGGER.debug("Processing batch of {} checksums", batch.size());
        var batchByPath = batch.stream().collect(Collectors.groupingBy(QueueEntry::sourceUrl));

        for (var entry : batchByPath.entrySet()) {
            ConcurrentHashMap<QueueEntry, Collection<String>> batchForPath = createBatch(entry.getValue());
            Map<String, PncBuild> foundBuilds = pncBuildFinder.findBuilds(batchForPath);
            if (!foundBuilds.isEmpty()) {
                resultAggregator.mergeBatchResults(globalResults.get(entry.getKey()), foundBuilds);
            }
        }
    }

    private ConcurrentHashMap<QueueEntry, Collection<String>> createBatch(List<QueueEntry> queueEntries) {
        ConcurrentHashMap<QueueEntry, Collection<String>> batchMap = new ConcurrentHashMap<>();
        for (QueueEntry queueEntry : queueEntries) {
            batchMap.computeIfAbsent(queueEntry, k -> ConcurrentHashMap.newKeySet())
                    .add(queueEntry.checksum().getFilename());
        }
        return batchMap;
    }
}
