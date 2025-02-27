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

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.redhat.red.build.koji.KojiClientException;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
@Disabled
class CompletableFutureParallelismTest {
    @Inject
    ManagedExecutor executor;

    @Test
    void testParallelExecution() {
        List<Integer> ids = List.of(5, 2, 1, 4, 3);
        CompletableFuture<List<Integer>> future = ids.stream().map(id -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100L * id);
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            }

            System.out.println("testParallelExecution: Finishing execution " + id);
            return id;
        }, executor)).collect(collectingAndThen(toList(), this::executeFutures));

        List<Integer> resultList = future.join();
        int result = resultList.stream().reduce(0, Integer::sum);
        assertEquals(1 + 2 + 3 + 4 + 5, result);
    }

    @Test
    void testParallelShortCircuitOnException() {
        List<Integer> ids = List.of(5, 2, 1, 4, 3);
        long timeBefore = new Date().getTime();
        CompletableFuture<List<Integer>> future = ids.stream().map(id -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100L * id);
                if (id == 1) {
                    throw new KojiClientException("ID 1 found");
                }
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            } catch (KojiClientException e) {
                throw new CompletionException(e);
            }

            System.out.println("testParallelShortCircuitOnException: Finishing execution " + id);
            return id;
        }, executor)).collect(collectingAndThen(toList(), this::executeFutures));

        try {
            future.join();
            fail("The results was joined successfully! It should have failed.");
        } catch (CompletionException e) {
            long timeAfter = new Date().getTime();
            assertInstanceOf(KojiClientException.class, e.getCause());
            assertTrue((timeAfter - timeBefore) < 200);
        }
    }

    // CompletableFuture.cancel doesn't do its job, and it is not possible to cancel running operations using it
    @Test
    void testParallelCancel() {
        List<Integer> ids = List.of(5, 2, 1, 4, 3);
        long timeBefore = new Date().getTime();
        CompletableFuture<List<Integer>> future = ids.stream().map(id -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100L * id);
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            }

            System.out.println("testParallelCancel: Finishing execution " + id);
            return id;
        }, executor)).collect(collectingAndThen(toList(), this::executeFutures));

        executor.runAsync(() -> {
            try {
                Thread.sleep(10L);
                System.out.println("Will cancel");
                assertTrue(future.cancel(true));
            } catch (InterruptedException e) {
                fail("Sleep was interrupted!", e);
            }
        });

        try {
            System.out.println("Will join");
            future.join();
            fail("The results was joined successfully! It should have been cancelled.");
        } catch (CompletionException e) {
            System.out.println("Verifying cancel");
            assertInstanceOf(CancellationException.class, e.getCause());
            long timeAfter = new Date().getTime();
            assertTrue((timeAfter - timeBefore) < 100);
        }
    }

    private CompletableFuture<List<Integer>> executeFutures(List<CompletableFuture<Integer>> initialFutures) {
        CompletableFuture<List<Integer>> result = initialFutures.stream()
                .collect(
                        collectingAndThen(
                                toList(),
                                // Creates a new CompletableFuture, which will complete when all the included futures
                                // are finished
                                // and waits for it to complete
                                futures -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                        .thenApply(
                                                ___ -> futures.stream()
                                                        .map(CompletableFuture::join) // get
                                                        .collect(Collectors.toList()))));

        // Short circuit the execution if there is an exception thrown in any of the CompletableFutures
        initialFutures.forEach(f -> f.handle((__, e) -> e != null && result.completeExceptionally(e)));
        return result;
    }

}
