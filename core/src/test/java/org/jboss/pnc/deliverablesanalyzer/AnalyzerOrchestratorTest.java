/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer;

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

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.deliverablesanalyzer.config.BuildSpecificConfig;
import org.jboss.pnc.deliverablesanalyzer.config.ConfigParser;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AnalyzerOrchestratorTest {

    @Inject
    AnalyzerOrchestrator orchestrator;

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
            Map<String, AnalyzerResult> results = invocation.getArgument(1);

            // Simulate processing
            AnalyzerBuild build = new AnalyzerBuild();
            build.setBuildId("test-id");
            build.setBuildSystemType(BuildSystemType.PNC);
            results.get("http://example.com/file1.zip").foundBuilds().put("test-id", build);
            return null;
        }).when(consumer).consume(any(), any());

        // When
        List<FinderResult> results = orchestrator.analyze("test-id", inputs, null);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("test-id", results.getFirst().getId());
        assertFalse(results.getFirst().getBuilds().isEmpty(), "Should contain the build added by the mock consumer");

        // Verify flow
        verify(producer, times(1)).produce(anyString(), any(), any());
        verify(consumer, times(1)).consume(any(), any());
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
    }
}
