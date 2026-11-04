/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
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

    @WithDefault("false")
    boolean disableKojiLookup();

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
