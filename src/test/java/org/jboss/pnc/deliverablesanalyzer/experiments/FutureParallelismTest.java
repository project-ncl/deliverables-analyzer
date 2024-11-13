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
package org.jboss.pnc.deliverablesanalyzer.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * @author Jakub Bartecek
 */
@QuarkusTest
@Disabled
class FutureParallelismTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FutureParallelismTest.class);

    @Inject
    ManagedExecutor executor;

    @Test
    void testParallelExecution() throws ExecutionException {
        // given
        List<Integer> ids = List.of(5, 2, 1, 4, 3);
        List<Integer> results = new ArrayList<>();
        long timeBefore = new Date().getTime();

        // when
        List<Future<Integer>> submittedTasks = ids.stream().map(id -> executor.submit(() -> {
            try {
                Thread.sleep(100L * id);
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            }

            LOGGER.info("Finishing execution {}", id);
            return id;
        })).collect(Collectors.toList());

        awaitResults(results, submittedTasks, new CancelWrapper());

        // then
        long timeAfter = new Date().getTime();
        LOGGER.info("Time elapsed {}ms", (timeAfter - timeBefore));
        assertTrue((timeAfter - timeBefore) < 600);

        int result = results.stream().reduce(0, Integer::sum);
        assertEquals(1 + 2 + 3 + 4 + 5, result);
    }

    @Test
    void testParallelShortCircuitOnException() {
        // given
        List<Integer> ids = List.of(5, 2, 1, 4, 3);
        List<Integer> results = new ArrayList<>();
        long timeBefore = new Date().getTime();

        // when
        List<Future<Integer>> submittedTasks = ids.stream().map(id -> executor.submit(() -> {
            LOGGER.info("Execution {} started", id);
            try {
                Thread.sleep(100L * id);

                if (id == 1) {
                    throw new KojiClientException("ID 1 found");
                }
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            }

            LOGGER.info("Finishing execution {}", id);
            return id;
        })).collect(Collectors.toList());

        try {
            awaitResults(results, submittedTasks, new CancelWrapper());
            fail("The results was awaited successfully! It should have failed.");
        } catch (ExecutionException e) {
            long timeAfter = new Date().getTime();
            assertInstanceOf(KojiClientException.class, e.getCause());
            assertTrue((timeAfter - timeBefore) < 200);
        }
    }

    @Test
    void testParallelCancel() {
        // given
        List<Integer> ids = List.of(5, 2, 1, 4, 3);
        List<Integer> results = new ArrayList<>();
        long timeBefore = new Date().getTime();

        // when
        List<Future<Integer>> submittedTasks = ids.stream().map(id -> executor.submit(() -> {
            LOGGER.info("Execution {} started", id);
            try {
                Thread.sleep(100L * id);
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            }

            System.out.println("Finishing execution " + id);
            return id;
        })).collect(Collectors.toList());

        CancelWrapper cancelWrapper = new CancelWrapper();
        executor.runAsync(() -> {
            try {
                Thread.sleep(20);
                System.out.println("Will cancel");
                cancelWrapper.cancel();
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            }
        });

        try {
            awaitResults(results, submittedTasks, cancelWrapper);
            fail("The results was joined successfully! It should have been cancelled.");
        } catch (CancellationException | ExecutionException e) {
            long timeAfter = new Date().getTime();
            System.out.println("Verifying cancel");
            assertInstanceOf(CancellationException.class, e);
            LOGGER.info("Time elapsed {}ms", (timeAfter - timeBefore));
            assertTrue((timeAfter - timeBefore) < 200);
        }
    }

    private void awaitResults(List<Integer> results, List<Future<Integer>> submittedTasks, CancelWrapper cancelWrapper)
            throws CancellationException, ExecutionException {

        int total = submittedTasks.size();
        int done = 0;
        Iterator<Future<Integer>> it = submittedTasks.iterator();

        while (done < total) {
            while (it.hasNext()) {
                try {
                    Future<Integer> futureTask = it.next();
                    if (futureTask.isDone()) {
                        it.remove();
                        results.add(futureTask.get());
                        done++;
                    } else {
                        Thread.sleep(10L);
                    }
                } catch (InterruptedException e) {
                    LOGGER.warn("Sleeping while awaiting results was interrupted", e);
                }
            }
            it = submittedTasks.iterator();

            if (cancelWrapper.isCancelled()) {
                LOGGER.info("Cancelling all remaining tasks: {}", submittedTasks.size());
                it.forEachRemaining(f -> f.cancel(true));
                LOGGER.info("All remaining tasks were cancelled");
                throw new CancellationException("Operation was cancelled manually");
            }
        }
    }

    private static final class CancelWrapper {
        private boolean cancelled = false;

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
