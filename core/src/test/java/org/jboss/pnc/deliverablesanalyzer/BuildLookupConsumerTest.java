/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import jakarta.inject.Inject;

import org.jboss.pnc.deliverablesanalyzer.core.ResultAggregator;
import org.jboss.pnc.deliverablesanalyzer.core.ScannedArtifact;
import org.jboss.pnc.deliverablesanalyzer.koji.KojiBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksummedFile;
import org.jboss.pnc.deliverablesanalyzer.pnc.PncBuildFinder;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class BuildLookupConsumerTest {

    @Inject
    BuildLookupConsumer consumer;

    @InjectMock
    PncBuildFinder pncBuildFinder;

    @InjectMock
    KojiBuildFinder kojiBuildFinder;

    @InjectMock
    ResultAggregator resultAggregator;

    @Test
    void testConsumeFlow() throws InterruptedException {
        // Given
        BlockingQueue<ScannedArtifact> queue = new LinkedBlockingQueue<>();
        String path = "http://test";
        ChecksummedFile checksummedFile = new ChecksummedFile("123", "123", "123", "file.jar", 100L);

        queue.put(new ScannedArtifact(path, checksummedFile, Collections.emptyList()));
        queue.put(ScannedArtifact.POISON_PILL); // Signal to stop

        Map<String, AnalyzerResult> globalResults = new ConcurrentHashMap<>();
        globalResults.put(path, AnalyzerResult.init());

        // Mock Finder to return empty map
        when(pncBuildFinder.findBuilds(any())).thenReturn(AnalyzerResult.empty());
        when(kojiBuildFinder.findBuilds(any())).thenReturn(AnalyzerResult.empty());

        // When
        consumer.consume(queue, globalResults);

        // Then
        // Should have called PncBuildFinder once for the batch
        verify(pncBuildFinder, timeout(2000).times(1)).findBuilds(any());
        verify(kojiBuildFinder, timeout(2000).times(1)).findBuilds(any());

        // Should have called cleanUp at the end
        verify(resultAggregator, timeout(2000).times(1)).cleanUp(any());
    }
}
