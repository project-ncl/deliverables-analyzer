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
package org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jboss.pnc.api.deliverablesanalyzer.dto.ArtifactType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Artifact entity with additional information needed for analysis
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzerArtifact {

    private BuildSystemType buildSystemType;
    private String inputPath;
    private Checksum checksum;

    private Collection<String> filenames = new ArrayList<>();
    private Collection<String> unmatchedFilenames = new ArrayList<>();
    private List<LicenseInfo> licenses = Collections.emptyList();

    // Build Properties
    private String buildId;
    private String buildNvr;
    private boolean isImport;

    // Artifact Properties
    private String systemArtifactId;
    private String artifactFilename;
    private Long artifactSize;
    private ArtifactType artifactType;
}
