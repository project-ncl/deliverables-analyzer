/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.analyzer;

import java.util.ArrayList;
import java.util.List;

import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Build entity with additional information needed for analysis
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzerBuild {

    private String buildId;
    private String buildNvr;
    private BuildSystemType buildSystemType;
    private boolean isImport;

    private List<AnalyzerArtifact> builtArtifacts = new ArrayList<>();

    public static AnalyzerBuild fromPncBuild(AnalyzerArtifact pncArtifact) {
        AnalyzerBuild analyzerBuild = new AnalyzerBuild();
        analyzerBuild.buildSystemType = BuildSystemType.PNC;
        analyzerBuild.buildId = pncArtifact.getBuildId();
        analyzerBuild.isImport = pncArtifact.isImport();
        return analyzerBuild;
    }

    public static AnalyzerBuild fromKojiBuild(AnalyzerArtifact kojiArtifact) {
        AnalyzerBuild analyzerBuild = new AnalyzerBuild();
        analyzerBuild.buildSystemType = BuildSystemType.BREW;
        analyzerBuild.buildId = kojiArtifact.getBuildId();
        analyzerBuild.buildNvr = kojiArtifact.getBuildNvr();
        analyzerBuild.isImport = kojiArtifact.isImport();
        return analyzerBuild;
    }
}
