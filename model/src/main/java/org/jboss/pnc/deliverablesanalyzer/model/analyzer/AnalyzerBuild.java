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
package org.jboss.pnc.deliverablesanalyzer.model.analyzer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;

import java.util.ArrayList;
import java.util.List;

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
