/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.analyzer;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;

public record AnalyzerResult(Map<String, AnalyzerBuild> foundBuilds, Set<AnalyzerArtifact> notFoundArtifacts) {
    private static final AnalyzerResult EMPTY = new AnalyzerResult(Collections.emptyMap(), Collections.emptySet());

    public static AnalyzerResult empty() {
        return EMPTY;
    }

    public static AnalyzerResult init() {
        return new AnalyzerResult(new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
    }

    public static AnalyzerResult of(Map<String, AnalyzerBuild> foundBuilds, Set<AnalyzerArtifact> notFoundArtifacts) {
        return new AnalyzerResult(foundBuilds, notFoundArtifacts);
    }
}
