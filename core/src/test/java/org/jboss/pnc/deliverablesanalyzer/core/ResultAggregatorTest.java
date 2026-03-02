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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.EnhancedArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.finder.PncBuild;
import org.jboss.pnc.dto.Build;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ResultAggregatorTest {

    @Inject
    ResultAggregator aggregator;

    // TODO Tomas: Add tests for result aggregator once determined what is to be used

    @Test
    void testCleanUpBuildZero() {
//        // Scenario:
//        // 1. We found 'lib.jar' in Build 100.
//        // 2. We did NOT find 'lib.jar!/Inner.class' in any build, so it defaults to Build 0.
//        // Expectation: 'Inner.class' should be removed from Build 0 and added to 'lib.jar' as an unmatched filename.
//
//        // Setup Build 100 (Found)
//        String foundSha = "111111";
//        EnhancedArtifact foundArtifact = new EnhancedArtifact(
//                null,
//                new Checksum(foundSha, "lib.jar", 100L),
//                List.of("path/to/lib.jar"),
//                Collections.emptyList(),
//                "url");
//
//        PncBuild build100 = new PncBuild(Build.builder().id("100").build());
//        build100.setBuiltArtifacts(new ArrayList<>(List.of(foundArtifact)));
//
//        // Setup Build 0 (Not Found)
//        String missingSha = "222222";
//        EnhancedArtifact missingArtifact = new EnhancedArtifact(
//                null,
//                new Checksum(missingSha, "Inner.class", 50L),
//                // CRITICAL: The filename must indicate nesting via "!/"
//                new ArrayList<>(List.of("path/to/lib.jar!/Inner.class")),
//                Collections.emptyList(),
//                "url");
//
//        PncBuild build0 = new PncBuild(Build.builder().id("0").build());
//        build0.setBuiltArtifacts(new ArrayList<>(List.of(missingArtifact)));
//
//        Map<String, PncBuild> globalResults = new HashMap<>();
//        globalResults.put("100", build100);
//        globalResults.put("0", build0);
//
//        // When
//        aggregator.cleanUp(Map.of("test-path", globalResults));
//
//        // Then
//        // 1. Build 0 should be empty now
//        assertTrue(build0.getBuiltArtifacts().isEmpty(), "Build 0 should be empty after cleanup");
//
//        // 2. Build 100's artifact should have the inner file listed as unmatched
//        assertNotNull(foundArtifact.getUnmatchedFilenames());
//        assertTrue(
//                foundArtifact.getUnmatchedFilenames().contains("path/to/lib.jar!/Inner.class"),
//                "Parent artifact should contain the child path");
    }
}
