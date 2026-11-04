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
public class RpmAnalyzerArtifact extends AnalyzerArtifact {
    private String name;
    private String version;
    private String release;
    private String arch;

    public RpmAnalyzerArtifact() {
        setArtifactType(ArtifactType.RPM);
    }
}
