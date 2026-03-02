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
import org.jboss.pnc.deliverablesanalyzer.core.PncBuildFinder;
import org.jboss.pnc.deliverablesanalyzer.core.QueueEntry;
import org.jboss.pnc.deliverablesanalyzer.core.ResultAggregator;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.PncBuild;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class BuildLookupConsumerTest {

    @Inject
    BuildLookupConsumer consumer;

    @InjectMock
    PncBuildFinder pncBuildFinder;

    @InjectMock
    ResultAggregator resultAggregator;

    @Test
    void testConsumeFlow() throws InterruptedException {
        // Given
        BlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>();
        String path = "http://test";
        Checksum checksum = new Checksum("123", "file.jar", 100L);

        queue.put(new QueueEntry(path, checksum, Collections.emptyList()));
        queue.put(QueueEntry.POISON_PILL); // Signal to stop

        Map<String, Map<String, PncBuild>> globalResults = new ConcurrentHashMap<>();
        globalResults.put(path, new ConcurrentHashMap<>());

        // Mock Finder to return empty map
        when(pncBuildFinder.findBuilds(any())).thenReturn(Collections.emptyMap());

        // When
        consumer.consume(queue, globalResults);

        // Then
        // 1. Should have called PncBuildFinder once for the batch
        verify(pncBuildFinder, times(1)).findBuilds(any());

        // 2. Should have called cleanUp at the end
        verify(resultAggregator, times(1)).cleanUp(any());
    }
}
