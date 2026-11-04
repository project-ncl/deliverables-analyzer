/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.core;

import java.util.Collections;
import java.util.List;

import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksummedFile;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;

public record ScannedArtifact(String sourceUrl, ChecksummedFile file, List<LicenseInfo> licenses) {

    // Poison Pill Constant
    public static final ScannedArtifact POISON_PILL = new ScannedArtifact(null, null, Collections.emptyList());

    public ScannedArtifact {
        licenses = (licenses != null) ? licenses : Collections.emptyList();
    }
}
