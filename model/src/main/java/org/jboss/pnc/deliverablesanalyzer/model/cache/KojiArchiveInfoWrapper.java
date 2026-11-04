/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.cache;

import java.util.Collections;
import java.util.List;

import org.infinispan.protostream.annotations.ProtoField;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;

public record KojiArchiveInfoWrapper(@ProtoField(number = 1) List<KojiArchiveInfo> data) {

    public KojiArchiveInfoWrapper {
        data = data != null ? data : Collections.emptyList();
    }
}
