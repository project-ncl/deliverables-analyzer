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
package org.jboss.pnc.deliverablesanalyzer.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.pnc.build.finder.core.BuildCheckedEvent;
import org.jboss.pnc.build.finder.core.BuildFinderListener;
import org.jboss.pnc.build.finder.core.ChecksumsComputedEvent;
import org.jboss.pnc.build.finder.core.DistributionAnalyzerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public class FinderStatus implements DistributionAnalyzerListener, BuildFinderListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinderStatus.class);

    @JsonIgnore
    @NotNull
    @PositiveOrZero
    private final AtomicInteger done;

    @JsonIgnore
    @NotNull
    private final AtomicInteger total;

    @JsonIgnore
    @NotNull
    private final Map<String, BuildCheckedEvent> map;

    public FinderStatus() {
        done = new AtomicInteger(0);
        total = new AtomicInteger(-1);
        map = new ConcurrentHashMap<>();
    }

    @PositiveOrZero
    public int getPercent() {
        int totalInt = total.intValue();
        int doneInt = done.intValue();

        if (totalInt <= 0 || doneInt == 0) {
            return 0;
        }

        if (doneInt > totalInt) {
            LOGGER.error("Number of checked checksums {} cannot be greater than total {}", doneInt, totalInt);
            doneInt = totalInt;
        }

        int percent = (int) (((double) doneInt / (double) totalInt) * 100.0D);

        LOGGER.debug("Progress: {} / {} = {}%", doneInt, totalInt, percent);

        return percent;
    }

    @Override
    public void buildChecked(BuildCheckedEvent event) {
        int totalInt = total.intValue();
        int doneInt = done.intValue();

        if (totalInt >= 0 && doneInt == totalInt) {
            map.clear();
            return;
        }

        LOGGER.debug("Checksum: {}, Build system: {}", event.getChecksum(), event.getBuildSystem());

        // FIXME: Hash map needed because we get multiple events for the same checksum
        map.computeIfAbsent(event.getChecksum().getFilename(), k -> {
            done.incrementAndGet();
            return event;
        });
    }

    @Override
    public void checksumsComputed(ChecksumsComputedEvent event) {
        total.set(event.getCount());
    }
}
