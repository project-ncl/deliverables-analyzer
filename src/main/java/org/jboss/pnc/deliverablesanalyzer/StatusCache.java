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

import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.collections4.map.PassiveExpiringMap;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

@Dependent
public class StatusCache<K, V> extends PassiveExpiringMap<K, V> {
    @Serial
    private static final long serialVersionUID = -5602712310506571554L;

    private static final long TIME_TO_LIVE_MILLIS = Duration.ofDays(1L).toMillis();

    @Inject
    public StatusCache() {
        super(TIME_TO_LIVE_MILLIS, new ConcurrentHashMap<>());
    }
}
