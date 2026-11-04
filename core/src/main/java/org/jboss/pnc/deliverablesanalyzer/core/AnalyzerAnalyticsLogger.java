/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AnalyzerAnalyticsLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzerAnalyticsLogger.class);

    public void logFinalAnalyzerInformation(Map<String, AnalyzerResult> results, long duration) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }

        // Flatten all builds and artifacts across all analyzed URLs
        Set<AnalyzerBuild> allBuilds = results.values()
                .stream()
                .flatMap(result -> result.foundBuilds().values().stream())
                .collect(Collectors.toSet());

        Set<AnalyzerArtifact> allArtifacts = results.values()
                .stream()
                .flatMap(
                        result -> Stream.concat(
                                result.foundBuilds().values().stream().flatMap(b -> b.getBuiltArtifacts().stream()),
                                result.notFoundArtifacts().stream()))
                .collect(Collectors.toSet());

        Set<String> uniqueSpdxLicenses = allArtifacts.stream()
                .flatMap(artifact -> artifact.getLicenses().stream())
                .map(LicenseInfo::getSpdxLicenseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));

        List<AnalyzerBuild> buildsWithLicenses = allBuilds.stream()
                .filter(
                        build -> build.getBuiltArtifacts()
                                .stream()
                                .anyMatch(
                                        artifact -> artifact.getLicenses() != null
                                                && !artifact.getLicenses().isEmpty()))
                .toList();

        List<AnalyzerArtifact> artifactsWithLicenses = allArtifacts.stream()
                .filter(artifact -> artifact.getLicenses() != null && !artifact.getLicenses().isEmpty())
                .toList();

        // Compute & Print Results
        int numTotalBuilds = allBuilds.size();
        int numBuildsWithLicenses = buildsWithLicenses.size();
        int buildLicensePercent = numTotalBuilds == 0 ? 0
                : (int) Math.round((numBuildsWithLicenses * 100.0) / numTotalBuilds);

        int numTotalArtifacts = allArtifacts.size();
        int numArtifactsWithLicenses = artifactsWithLicenses.size();
        int artifactLicensePercent = numTotalArtifacts == 0 ? 0
                : (int) Math.round((numArtifactsWithLicenses * 100.0) / numTotalArtifacts);

        LOGGER.info("Analysis complete. Found {} builds in {} ms.", allBuilds, duration);

        LOGGER.info(
                "Added {} unique SPDX licenses to builds: {}",
                uniqueSpdxLicenses.size(),
                String.join(", ", uniqueSpdxLicenses));

        LOGGER.info(
                "{} / {} = {}% of builds have license information",
                numBuildsWithLicenses,
                numTotalBuilds,
                buildLicensePercent);

        LOGGER.info(
                "{} / {} = {}% of archives have license information",
                numArtifactsWithLicenses,
                numTotalArtifacts,
                artifactLicensePercent);
    }
}
