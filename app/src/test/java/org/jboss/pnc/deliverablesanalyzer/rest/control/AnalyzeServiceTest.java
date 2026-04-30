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
package org.jboss.pnc.deliverablesanalyzer.rest.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisReport;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.dto.HeartbeatConfig;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.common.concurrent.HeartbeatScheduler;
import org.jboss.pnc.deliverablesanalyzer.AnalyzerOrchestrator;
import org.junit.jupiter.api.Test;

import io.quarkus.infinispan.client.Remote;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AnalyzeServiceTest {

    @Inject
    AnalyzeService analyzeService;

    @InjectMock
    HeartbeatScheduler heartbeatScheduler;

    @InjectMock
    AnalyzerOrchestrator orchestrator;

    @InjectMock
    CallbackService callbackService;

    @InjectMock
    @Remote("cancel-events")
    RemoteCache<String, String> cancelEventsCacheMock;

    @Test
    void testAnalyzeStartsJobAndHeartbeat() {
        // Given
        String id = "job-123";
        AnalyzePayload payload = AnalyzePayload.builder()
                .operationId(id)
                .urls(List.of("http://test.com/file.zip"))
                .heartbeat(
                        new HeartbeatConfig(
                                new Request(Request.Method.POST, URI.create("http://hb")),
                                1000L,
                                java.util.concurrent.TimeUnit.MILLISECONDS)) // Correct HeartbeatConfig
                .config("config-json")
                .build();

        // Mock orchestrator success
        when(orchestrator.analyze(anyString(), anySet(), anyString())).thenReturn(Collections.emptyList());

        // When
        String resultId = analyzeService.analyze(payload);

        // Then
        assertEquals(id, resultId);

        // Verify Heartbeat started (Using HeartbeatConfig now)
        verify(heartbeatScheduler).subscribeRequest(eq(id), any(HeartbeatConfig.class));

        // Verify Orchestrator called
        verify(orchestrator, timeout(5000)).analyze(eq(id), anySet(), eq("config-json"));

        // Verify Heartbeat stopped
        verify(heartbeatScheduler, timeout(5000)).unsubscribeRequest(id);
    }

    @Test
    void testAnalyzeThrowsBadRequestOnMissingUrls() {
        AnalyzePayload payload = AnalyzePayload.builder().operationId("id").build();

        assertThrows(BadRequestException.class, () -> analyzeService.analyze(payload));
    }

    @Test
    void testCallbackPerformedOnSuccess() {
        // Given
        AnalyzePayload payload = AnalyzePayload.builder()
                .operationId("cb-test")
                .urls(List.of("http://test.com"))
                .callback(new Request(Request.Method.POST, URI.create("http://callback"))) // Correct Request object
                .build();

        when(orchestrator.analyze(any(), any(), any())).thenReturn(List.of(mock(FinderResult.class)));

        // When
        analyzeService.analyze(payload);

        // Then
        // Verify we pass the Request object to the callback service
        verify(callbackService, timeout(1000)).performCallback(eq(payload.getCallback()), any(AnalysisReport.class));
    }

    @Test
    void testCancelBroadcastsEvent() {
        // When
        boolean success = analyzeService.cancel("cancel-id");

        // Then
        assertTrue(success);
        verify(cancelEventsCacheMock).put("cancel-id", "CANCEL_REQUESTED");
    }

    @Test
    void testCancelReturnsFalseOnCacheFailure() {
        // Given
        when(cancelEventsCacheMock.put(anyString(), anyString())).thenThrow(new RuntimeException("Infinispan down"));

        // When
        boolean success = analyzeService.cancel("cancel-id");

        // Then
        assertFalse(success, "Cancel should return false when cache put fails");
    }

    @Test
    void testTryCancelLocalJobSuccess() throws InterruptedException {
        // Given
        AnalyzePayload payload = AnalyzePayload.builder()
                .operationId("local-cancel-id")
                .urls(List.of("http://url"))
                .build();

        doAnswer(invocation -> {
            Thread.sleep(5000); // Hang for 5 seconds
            return null;
        }).when(orchestrator).analyze(anyString(), anySet(), any());

        analyzeService.analyze(payload);
        Thread.sleep(200);

        // When - simulate receiving the event from Infinispan listener
        boolean cancelled = analyzeService.tryCancelLocalJob("local-cancel-id");

        // Then
        assertTrue(cancelled, "The local job should have been successfully cancelled");
        verify(heartbeatScheduler).unsubscribeRequest("local-cancel-id");
    }

    @Test
    void testTryCancelLocalJobIgnoresUnknownId() {
        // When we try to cancel a job that isn't running on this pod
        // Then it should silently ignore it without throwing exceptions
        analyzeService.tryCancelLocalJob("non-existent-id");
    }
}
