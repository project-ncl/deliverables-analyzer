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
package org.jboss.pnc.deliverablesanalyzer.core;

import java.nio.file.Path;

import static org.jboss.pnc.deliverablesanalyzer.utils.AnalyzerUtils.getUserHome;

public final class ConfigDefaults {

    private static final Path USER_HOME = getUserHome();
    public static final Path CONFIG_PATH = USER_HOME.resolve(".build-finder");
    public static final Path CACHE_LOCATION = CONFIG_PATH.resolve("cache");

    private ConfigDefaults() {
        // Prevent instantiation
    }
}
