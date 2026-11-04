/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.cache;

import java.util.Collections;
import java.util.List;

import org.infinispan.protostream.annotations.ProtoField;

public record ArchiveInfo(@ProtoField(number = 1) List<ArchiveEntry> entries) {

    public ArchiveInfo {
        entries = entries != null ? entries : Collections.emptyList();
    }
}
