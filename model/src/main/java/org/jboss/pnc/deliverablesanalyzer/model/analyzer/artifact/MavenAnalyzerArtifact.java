/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact;

import org.jboss.pnc.api.deliverablesanalyzer.dto.ArtifactType;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MavenAnalyzerArtifact extends AnalyzerArtifact {
    private String groupId;
    private String artifactId;
    private String type;
    private String version;
    private String classifier;

    public MavenAnalyzerArtifact() {
        setArtifactType(ArtifactType.MAVEN);
    }
}
