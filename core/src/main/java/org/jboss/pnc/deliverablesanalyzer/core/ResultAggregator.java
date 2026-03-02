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
import org.jboss.pnc.deliverablesanalyzer.model.finder.EnhancedArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.finder.PncBuild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ResultAggregator {

    private static final String BUILD_ID_ZERO = "0";
    private static final String BANG_SLASH = "!/";

    /**
     * Merges a batch of found builds into the accumulated results for a specific path
     */
    public void mergeBatchResults(Map<String, PncBuild> pathResults, Map<String, PncBuild> foundBuilds) {
        foundBuilds.forEach((buildId, incomingBuild) -> {
            // deduplicateArtifacts(incomingBuild); // TODO Tomas: Use or not?

            pathResults.merge(buildId, incomingBuild, (existingBuild, newBuild) -> {
                mergeArtifacts(existingBuild, newBuild);
                return existingBuild;
            });
        });
    }

    /**
     * Performs final clean-up across all results, removing nested artifacts from Build Zero
     */
    public void cleanUp(Map<String, Map<String, PncBuild>> globalResults) {
        for (Map<String, PncBuild> buildMap : globalResults.values()) {
             cleanUpBuildZero(buildMap); // TODO Tomas: Cleanup or not?
        }
    }

    // --- Internal Logic ---

    private void deduplicateArtifacts(PncBuild build) {
        if (build.getBuiltArtifacts() == null || build.getBuiltArtifacts().isEmpty()) {
            return;
        }

        Map<String, EnhancedArtifact> uniqueMap = new LinkedHashMap<>();

        for (EnhancedArtifact artifact : build.getBuiltArtifacts()) {
            String checksum = artifact.getChecksum().getValue();
            EnhancedArtifact existing = uniqueMap.get(checksum);

            if (existing != null) {
                // Duplicate found - merge the filenames into the existing artifact
                existing.getFilenames().addAll(artifact.getFilenames());

                if (artifact.getUnmatchedFilenames() != null) {
                    if (existing.getUnmatchedFilenames() == null) {
                        existing.setUnmatchedFilenames(new HashSet<>());
                    }
                    existing.getUnmatchedFilenames().addAll(artifact.getUnmatchedFilenames());
                }
            } else {
                uniqueMap.put(checksum, artifact);
            }
        }

        build.setBuiltArtifacts(new ArrayList<>(uniqueMap.values()));
    }

    private void mergeArtifacts(PncBuild existingBuild, PncBuild incomingBuild) {
        if (incomingBuild.getBuiltArtifacts() == null) {
            return;
        }

        if (existingBuild.getBuiltArtifacts() == null) {
            existingBuild.setBuiltArtifacts(new ArrayList<>());
        }

        for (EnhancedArtifact incomingArtifact : incomingBuild.getBuiltArtifacts()) {
            EnhancedArtifact existingArtifact = existingBuild.getBuiltArtifacts()
                    .stream()
                    .filter(a -> a.getChecksum().getValue().equals(incomingArtifact.getChecksum().getValue()))
                    .findFirst()
                    .orElse(null);

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
            }
        }
    }

    private void cleanUpBuildZero(Map<String, PncBuild> buildMap) {
        PncBuild buildZero = buildMap.get(BUILD_ID_ZERO);
        if (buildZero == null || buildZero.getBuiltArtifacts() == null) {
            return;
        }

        // Index filenames (Map of filename -> artifact)
        Map<String, EnhancedArtifact> fileIndex = new HashMap<>();
        buildMap.values()
                .stream()
                .filter(b -> b.getBuiltArtifacts() != null)
                .flatMap(b -> b.getBuiltArtifacts().stream())
                .forEach(artifact -> {
                    for (String filename : artifact.getFilenames()) {
                        fileIndex.put(filename, artifact);
                    }
                });

        if (fileIndex.isEmpty()) {
            return;
        }

        // Iterate over not found artifacts
        Iterator<EnhancedArtifact> it = buildZero.getBuiltArtifacts().iterator();
        while (it.hasNext()) {
            EnhancedArtifact childArtifact = it.next();

            // Track files moved to parent
            List<String> filesToMove = new ArrayList<>();

            for (String filename : childArtifact.getFilenames()) {
                // Find parent in index
                String parentFilename = findParentInIndex(filename, fileIndex);

                // If parent exists, add files to parent and mark them for removal from child
                if (parentFilename != null) {
                    addUnmatchedFilename(fileIndex.get(parentFilename), filename);
                    if (parentFilename.contains(BANG_SLASH)) {
                        filesToMove.add(filename);
                    }
                }
            }

            // Remove moved files from the child
            if (!filesToMove.isEmpty()) {
                childArtifact.getFilenames().removeAll(filesToMove);
            }

            // If artifact empty, delete the artifact entirely
            if (childArtifact.getFilenames().isEmpty()) {
                it.remove();
            }
        }
    }

    private String findParentInIndex(String filename, Map<String, EnhancedArtifact> fileIndex) {
        int index = filename.lastIndexOf(BANG_SLASH);
        if (index == -1) {
            index = filename.length();
        }

        String parentFilename = filename.substring(0, index);

        if (fileIndex.containsKey(parentFilename)) {
            return parentFilename;
        }

        if (index == filename.length()) {
            return null;
        }

        return findParentInIndex(parentFilename, fileIndex);
    }

    private void addUnmatchedFilename(EnhancedArtifact enhancedArtifact, String filename) {
        if (enhancedArtifact.getUnmatchedFilenames() == null) {
            enhancedArtifact.setUnmatchedFilenames(new HashSet<>());
        }
        enhancedArtifact.getUnmatchedFilenames().add(filename);
    }
}
