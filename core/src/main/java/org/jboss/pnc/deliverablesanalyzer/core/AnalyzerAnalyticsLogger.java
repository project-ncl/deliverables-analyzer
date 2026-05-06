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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
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

        // Calculate & Print Built From Source Analytics
        long pncTotal = 0;
        long pncNotBuilt = 0;

        long brewTotal = 0;
        long brewNotBuilt = 0;

        long otherNotBuilt = 0;

        for (AnalyzerArtifact artifact : allArtifacts) {
            boolean isBuiltFromSource = artifact.getUnmatchedFilenames().isEmpty() && !artifact.isImport();

            if (artifact.getBuildSystemType() == null) {
                if (!isBuiltFromSource)
                    otherNotBuilt++;
            } else if (artifact.getBuildSystemType().equals(BuildSystemType.PNC)) {
                pncTotal++;
                if (!isBuiltFromSource)
                    pncNotBuilt++;
            } else if (artifact.getBuildSystemType().equals(BuildSystemType.BREW)) {
                brewTotal++;
                if (!isBuiltFromSource)
                    brewNotBuilt++;
            }
        }

        long totalNotBuilt = pncNotBuilt + brewNotBuilt + otherNotBuilt;

        LOGGER.info(
                "PNC artifacts: {} ({} artifacts not built from source), BREW artifacts: {} ({} artifacts not built from source), other artifacts not built from source: {}",
                pncTotal,
                pncNotBuilt,
                brewTotal,
                brewNotBuilt,
                otherNotBuilt);

        LOGGER.info("There are total {} artifacts not built from source!", totalNotBuilt);
    }
}
