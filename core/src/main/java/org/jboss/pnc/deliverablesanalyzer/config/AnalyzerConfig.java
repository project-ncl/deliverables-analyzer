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
package org.jboss.pnc.deliverablesanalyzer.config;

import java.net.URL;
import java.util.List;
import java.util.regex.Pattern;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "delan.analyzer")
public interface AnalyzerConfig {

    @WithDefault("false")
    boolean disableSpdxInit();

    @WithDefault("false")
    boolean disableCache();

    @WithDefault("false")
    boolean disableRecursion();

    @WithDefault("dll,dylib,ear,jar,jdocbook,jdocbook-style,kar,plugin,pom,rar,sar,so,war,xml,exe,msi,zip,rpm")
    List<String> archiveExtensions();

    @WithDefault("^(?!.*/pom\\.xml$).*/.*\\.xml$")
    List<Pattern> excludes();

    @WithDefault("10")
    int pncNumThreads();

    URL pncUrl();

    @WithDefault("12")
    int kojiNumThreads();

    @WithDefault("8")
    int kojiMulticallSize();

    URL kojiUrl();
}
