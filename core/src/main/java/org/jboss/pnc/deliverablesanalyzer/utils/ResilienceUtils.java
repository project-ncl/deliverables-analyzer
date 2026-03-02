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
package org.jboss.pnc.deliverablesanalyzer.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

public final class ResilienceUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResilienceUtils.class);

    private static final Duration RETRY_DURATION = Duration.ofMillis(500L);
    private static final int NUM_RETRIES = 3;

    private ResilienceUtils() {
        // Prevent instantiation
    }

    /**
     * Retries a supplier function a specified number of times with a delay between attempts.
     *
     * @param supplier The function to execute.
     * @param <T> The return type.
     * @return The result of the supplier.
     * @throws IllegalStateException if all retries fail.
     */
    public static <T> T retry(Supplier<T> supplier) {
        return retry(supplier, NUM_RETRIES, RETRY_DURATION.toMillis());
    }

    public static <T> T retry(Supplier<T> supplier, int maxRetries, long waitMillis) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return supplier.get();
            } catch (Exception e) {
                lastException = e;

                // If we are out of retries, break immediately (don't sleep)
                if (attempt == maxRetries) {
                    break;
                }

                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    lastException = ie;
                    break;
                }
            }
        }

        LOGGER.error("Retry attempt failed after {} of {} tries", maxRetries, maxRetries);

        throw new IllegalStateException("Operation failed after " + maxRetries + " retries", lastException);
    }
}
