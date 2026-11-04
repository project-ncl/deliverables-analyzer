/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import java.util.Comparator;

import org.infinispan.protostream.annotations.ProtoField;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LicenseInfo implements Comparable<LicenseInfo> {

    @ProtoField(number = 1)
    String comments;

    @ProtoField(number = 2)
    String distribution;

    @ProtoField(number = 3)
    String name;

    @ProtoField(number = 4)
    @EqualsAndHashCode.Exclude
    String url;

    @ProtoField(number = 5)
    String spdxLicenseId;

    @ProtoField(number = 6)
    String sourceUrl;

    private static final Comparator<LicenseInfo> COMPARATOR = Comparator
            .comparing(LicenseInfo::getSpdxLicenseId, Comparator.nullsFirst(String::compareTo))
            .thenComparing(LicenseInfo::getName, Comparator.nullsFirst(String::compareTo))
            .thenComparing(LicenseInfo::getSourceUrl, Comparator.nullsFirst(String::compareTo))
            .thenComparing(LicenseInfo::getDistribution, Comparator.nullsFirst(String::compareTo))
            .thenComparing(LicenseInfo::getComments, Comparator.nullsFirst(String::compareTo));

    /**
     * Implements comparison logic. Order: SPDX ID -> Name -> Source URL -> Distribution -> Comments.
     * Note: 'url' is ignored in comparison.
     */
    @Override
    public int compareTo(LicenseInfo other) {
        return COMPARATOR.compare(this, other);
    }
}
