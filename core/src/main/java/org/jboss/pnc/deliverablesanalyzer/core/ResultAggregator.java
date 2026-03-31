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

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ResultAggregator {

    private static final String BANG_SLASH = "!/";

    /**
     * Merges a batch of found builds into the accumulated results for a specific path
     */
    public void mergeBatchResults(AnalyzerResult pathResults, Map<String, AnalyzerBuild> foundBuilds) {
        // TODO Tomas: Problem if koji and pnc artifacts get the same build ID
        foundBuilds.forEach(
                (buildId, incomingBuild) -> pathResults.foundBuilds()
                        .merge(buildId, incomingBuild, (existingBuild, newBuild) -> {
                            mergeArtifacts(existingBuild, newBuild);
                            return existingBuild;
                        }));
    }

    /**
     * Performs final clean-up across all results, marking nested artifacts from Build Zero
     */
    public void cleanUp(Map<String, AnalyzerResult> globalResults) {
        for (AnalyzerResult pathResult : globalResults.values()) {
            cleanUpNotFound(pathResult);
        }
    }

    // --- Internal Logic ---

    private void mergeArtifacts(AnalyzerBuild existingBuild, AnalyzerBuild incomingBuild) {
        if (incomingBuild.getBuiltArtifacts() == null) {
            return;
        }

        if (existingBuild.getBuiltArtifacts() == null) {
            existingBuild.setBuiltArtifacts(new ArrayList<>());
        }

        // TODO Tomas: Assuming sha256 present - not the case for rpm koji
        Map<String, AnalyzerArtifact> existingArtifactsByHash = existingBuild.getBuiltArtifacts()
                .stream()
                .collect(Collectors.toMap(a -> a.getChecksum().getSha256Value(), a -> a, (a1, a2) -> a1));

        for (AnalyzerArtifact incomingArtifact : incomingBuild.getBuiltArtifacts()) {
            AnalyzerArtifact existingArtifact = existingArtifactsByHash
                    .get(incomingArtifact.getChecksum().getSha256Value());

            if (existingArtifact != null) {
                // Merge filenames and unmatched filenames
                existingArtifact.getFilenames().addAll(incomingArtifact.getFilenames());

                if (incomingArtifact.getUnmatchedFilenames() != null) {
                    if (existingArtifact.getUnmatchedFilenames() == null) {
                        existingArtifact.setUnmatchedFilenames(new HashSet<>());
                    }
                    existingArtifact.getUnmatchedFilenames().addAll(incomingArtifact.getUnmatchedFilenames());
                }
            } else {
                existingBuild.getBuiltArtifacts().add(incomingArtifact);
                existingArtifactsByHash.put(incomingArtifact.getChecksum().getSha256Value(), incomingArtifact);
            }
        }
    }

    private void cleanUpNotFound(AnalyzerResult pathResult) {
        // Index filenames (Map of filename -> artifact)
        Map<String, AnalyzerArtifact> fileIndex = new HashMap<>();
        pathResult.foundBuilds()
                .values()
                .stream()
                .filter(b -> b.getBuiltArtifacts() != null)
                .flatMap(b -> b.getBuiltArtifacts().stream())
                .forEach(artifact -> {
                    for (String filename : artifact.getFilenames()) {
                        fileIndex.put(filename, artifact);
                    }
                });
        pathResult.notFoundArtifacts().forEach(artifact -> {
            for (String filename : artifact.getFilenames()) {
                fileIndex.put(filename, artifact);
            }
        });

        if (fileIndex.isEmpty()) {
            return;
        }

        // Iterate over not found artifacts
        for (AnalyzerArtifact childArtifact : pathResult.notFoundArtifacts()) {
            for (String filename : childArtifact.getFilenames()) {
                // Find parent in index
                String parentFilename = findParentInIndex(filename, fileIndex);

                // If parent exists, add files to parent as unmatched
                if (parentFilename != null) {
                    addUnmatchedFilename(fileIndex.get(parentFilename), filename);
                }
            }
        }
    }

    private String findParentInIndex(String filename, Map<String, AnalyzerArtifact> fileIndex) {
        String currentPath = filename;

        while (true) {
            int index = currentPath.lastIndexOf(BANG_SLASH);
            if (index == -1) {
                return null;
            }

            currentPath = currentPath.substring(0, index);
            if (fileIndex.containsKey(currentPath)) {
                return currentPath;
            }
        }
    }

    private void addUnmatchedFilename(AnalyzerArtifact analyzerArtifact, String filename) {
        if (analyzerArtifact.getUnmatchedFilenames() == null) {
            analyzerArtifact.setUnmatchedFilenames(new HashSet<>());
        }
        analyzerArtifact.getUnmatchedFilenames().add(filename);
    }
}
