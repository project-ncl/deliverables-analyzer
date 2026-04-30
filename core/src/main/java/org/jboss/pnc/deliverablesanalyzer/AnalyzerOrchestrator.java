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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.config.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.config.ConfigParser;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.utils.FinderResultCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.virtual.threads.VirtualThreads;

@ApplicationScoped
public class AnalyzerOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzerOrchestrator.class);

    @Inject
    FileChecksumProducer fileChecksumProducer;

    @Inject
    BuildLookupConsumer buildLookupConsumer;

    @Inject
    ConfigParser configParser;

    @Inject
    @VirtualThreads
    ExecutorService executorService;

    /**
     * Analyses the given input paths to find their corresponding builds in PNC.
     *
     * @param id Operation ID
     * @param inputPaths List of URLs or file paths to analyse
     * @return List of FinderResults (containing the identified builds with artifacts)
     */
    public List<FinderResult> analyze(String id, Set<String> inputPaths, String specificConfig) {
        // Setup Shared Resources
        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>(1000);
        Map<String, AnalyzerResult> results = new ConcurrentHashMap<>();
        inputPaths.forEach(path -> results.put(path, AnalyzerResult.init()));

        BuildSpecificConfig buildSpecificConfig = configParser.parseConfig(specificConfig);

        LOGGER.info("Starting analysis for {} inputs", inputPaths.size());
        long startTime = System.currentTimeMillis();

        // Create Virtual Thread Executor
        // Start Consumer
        CompletableFuture<Void> consumerTask = CompletableFuture
                .runAsync(() -> buildLookupConsumer.consume(queue, results), executorService);

        // Run Producers in Parallel (One task per input path)
        List<CompletableFuture<Void>> producerTasks = inputPaths.stream()
                .map(
                        path -> CompletableFuture.runAsync(
                                () -> fileChecksumProducer.produce(path, queue, buildSpecificConfig),
                                executorService))
                .toList();

        try {
            // Block until all files are downloaded, scanned, and queued
            CompletableFuture.allOf(producerTasks.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            LOGGER.error("Analysis failed or got cancelled during production phase", e);

            // Kill the tasks
            producerTasks.forEach(f -> f.cancel(true));
            consumerTask.cancel(true);

            // Wipe queue to stop processing checksums when error happens
            queue.clear();

            throw unwrapAndPropagate(e);
        } finally {
            // Shutdown Sequence
            try {
                if (!consumerTask.isDone() && !consumerTask.isCancelled()) {
                    // Whether producer succeeds or fails, we must stop the consumer
                    queue.put(QueueEntry.POISON_PILL);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while sending Poison Pill");
            }

            LOGGER.debug("Cleaning up VFS cache...");
            fileChecksumProducer.cleanupVfsCache();
        }

        // Wait for Consumer to Finish
        try {
            consumerTask.join();
        } catch (Exception e) {
            if (!consumerTask.isCancelled()) {
                LOGGER.error("Error waiting for consumer to finish", e);
                throw unwrapAndPropagate(e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info("Analysis complete. Found {} builds in {} ms.", results.size(), duration);

        return results.entrySet()
                .stream()
                .map(entry -> FinderResultCreator.createFinderResult(id, entry.getKey(), entry.getValue()))
                .toList();
    }

    private RuntimeException unwrapAndPropagate(Exception e) {
        Throwable cause = e;
        while (cause instanceof ExecutionException || cause instanceof CompletionException) {
            cause = cause.getCause() != null ? cause.getCause() : cause;
        }

        return switch (cause) {
            case ReasonedException re -> re;
            case CancellationException ce -> ce;
            case InterruptedException ie -> {
                Thread.currentThread().interrupt();
                yield new CancellationException("Analysis cancelled");
            }
            case null, default -> new ReasonedException(ResultStatus.SYSTEM_ERROR, "Analysis failed", cause);
        };
    }
}
