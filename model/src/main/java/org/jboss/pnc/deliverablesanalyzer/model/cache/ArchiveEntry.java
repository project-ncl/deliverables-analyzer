/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.cache;

import java.util.Collections;
import java.util.List;

import org.infinispan.protostream.annotations.ProtoField;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LocalFile;

public record ArchiveEntry(@ProtoField(number = 1) String sha256Checksum, @ProtoField(number = 2) String sha1Checksum,
        @ProtoField(number = 3) String md5Checksum, @ProtoField(number = 4) LocalFile file,
        @ProtoField(number = 5) List<LicenseInfo> licenses) {

    public ArchiveEntry {
        licenses = (licenses != null) ? licenses : Collections.emptyList();
    }
}
