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

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.deliverablesanalyzer.core.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.core.ConfigParser;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.model.finder.PncBuild;
import org.jboss.pnc.dto.Build;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class BuildFinderOrchestratorTest {

    @Inject
    BuildFinderOrchestrator orchestrator;

    @InjectMock
    FileChecksumProducer producer;

    @InjectMock
    BuildLookupConsumer consumer;

    @InjectMock
    ConfigParser configParser;

    @Test
    void testAnalyzeSuccess() {
        // Given
        Set<String> inputs = Set.of("http://example.com/file1.zip");
        when(configParser.parseConfig(any())).thenReturn(new BuildSpecificConfig(null, null));

        // Mock Producer: Do nothing (simulate successful queuing)
        doNothing().when(producer).produce(anyString(), any(), any());

        // Mock Consumer: Populate the results map directly to simulate finding a build
        doAnswer(invocation -> {
            BlockingQueue<QueueEntry> queue = invocation.getArgument(0);
            Map<String, Map<String, PncBuild>> results = invocation.getArgument(1);

            // Simulate processing
            Build build = Build.builder().id("test-id").build();
            results.get("http://example.com/file1.zip").put("100", new PncBuild(build));
            return null;
        }).when(consumer).consume(any(), any());

        // When
        List<FinderResult> results = orchestrator.analyze("test-id", inputs, null);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("test-id", results.get(0).getId());
        assertFalse(results.get(0).getBuilds().isEmpty(), "Should contain the build added by the mock consumer");

        // Verify flow
        verify(producer, times(1)).produce(anyString(), any(), any());
        verify(consumer, times(1)).consume(any(), any());
        verify(producer, times(1)).cleanupVfsCache(); // Ensure cleanup happens
    }

    @Test
    void testAnalyzeHandlesProducerFailure() {
        // Given
        Set<String> inputs = Set.of("bad-file");
        when(configParser.parseConfig(any())).thenReturn(new BuildSpecificConfig(null, null));

        // Mock Producer: Throw Exception
        doThrow(new RuntimeException("Download failed")).when(producer).produce(anyString(), any(), any());

        // When & Then
        ReasonedException thrown = assertThrows(ReasonedException.class, () -> {
            orchestrator.analyze("test-id", inputs, null);
        });

        assertTrue(thrown.getMessage().contains("Analysis failed"));
        // Ensure cleanup still runs
        verify(producer, times(1)).cleanupVfsCache();
    }
}
