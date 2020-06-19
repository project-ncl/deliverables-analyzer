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

import static org.jboss.pnc.build.finder.core.AnsiUtils.boldRed;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;

import org.jboss.pnc.build.finder.core.BuildCheckedEvent;
import org.jboss.pnc.build.finder.core.BuildFinderListener;
import org.jboss.pnc.build.finder.core.ChecksumsComputedEvent;
import org.jboss.pnc.build.finder.core.DistributionAnalyzerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class FinderStatus implements DistributionAnalyzerListener, BuildFinderListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinderStatus.class);

    @JsonIgnore
    @NotNull
    @PositiveOrZero
    private AtomicInteger d;

    @JsonIgnore
    @NotNull
    private AtomicInteger t;

    @JsonIgnore
    @NotNull
    private final Map<String, BuildCheckedEvent> h;

    public FinderStatus() {
        d = new AtomicInteger(0);
        t = new AtomicInteger(-1);
        h = new ConcurrentHashMap<>();
    }

    @PositiveOrZero
    public int getPercent() {
        int ti = t.intValue();
        int di = d.intValue();

        if (ti <= 0 || di == 0) {
            return 0;
        }

        if (di > ti) {
            LOGGER.error("Number of checked checksums {} cannot be greater than total {}", boldRed(di), boldRed(ti));
            di = ti;
        }

        int percent = (int) (((double) di / (double) ti) * 100);

        LOGGER.debug("Progress: {} / {} = {}%", di, ti, percent);

        return percent;
    }

    @Override
    public void buildChecked(BuildCheckedEvent event) {
        int di = d.intValue();
        int ti = t.intValue();

        if (di >= 0 && ti >= 0 && di == ti) {
            h.clear();
            return;
        }

        LOGGER.debug("Checksum: {}, Build system: {}", event.getChecksum(), event.getBuildSystem());

        // FIXME: Hash map needed because we get multiple events for the same checksum
        h.computeIfAbsent(event.getChecksum().getFilename(), k -> {
            d.incrementAndGet();
            return event;
        });
    }

    @Override
    public void checksumsComputed(ChecksumsComputedEvent event) {
        t.set(event.getCount());
    }
}
