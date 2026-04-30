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
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "analyzer")
public interface BuildConfig {

    @WithName("disable-spdx-init")
    @WithDefault("false")
    boolean disableSpdxInit();

    @WithName("disable-cache")
    @WithDefault("false")
    boolean disableCache();

    @WithName("cache-lifespan")
    @WithDefault("3600000") // 1 hour
    long cacheLifespan();

    @WithName("disable-recursion")
    @WithDefault("false")
    boolean disableRecursion();

    @WithName("archive-extensions")
    @WithDefault("dll,dylib,ear,jar,jdocbook,jdocbook-style,kar,plugin,pom,rar,sar,so,war,xml,exe,msi,zip,rpm")
    List<String> archiveExtensions();

    @WithName("excludes")
    @WithDefault("^(?!.*/pom\\.xml$).*/.*\\.xml$")
    List<Pattern> excludes();

    @WithName("pnc-num-threads")
    @WithDefault("10")
    int pncNumThreads();

    @WithName("pnc-url")
    URL pncURL();

    @WithName("koji-num-threads")
    @WithDefault("12")
    int kojiNumThreads();

    @WithName("koji-multicall-size")
    @WithDefault("8")
    int kojiMultiCallSize();

    @WithName("koji-url")
    URL kojiURL();
}
