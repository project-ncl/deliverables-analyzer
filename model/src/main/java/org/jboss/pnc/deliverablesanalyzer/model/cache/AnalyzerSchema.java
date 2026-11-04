/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.cache;

import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoSchema;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LocalFile;

@ProtoSchema(
        includeClasses = {
                ArchiveInfo.class,
                ArchiveEntry.class,
                LicenseInfo.class,
                LocalFile.class,
                KojiArchiveInfoAdapter.class,
                KojiArchiveInfoWrapper.class,
                KojiBuildAdapter.class,
                KojiRpmInfoAdapter.class },
        schemaPackageName = "org.jboss.pnc.analyzer")
public interface AnalyzerSchema extends GeneratedSchema {
}
