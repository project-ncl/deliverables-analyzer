/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.jboss.pnc.api.deliverablesanalyzer.dto.ArtifactType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksummedFile;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Artifact entity with additional information needed for analysis
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzerArtifact {

    private BuildSystemType buildSystemType;
    private String inputPath;
    private ChecksummedFile checksummedFile;

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
