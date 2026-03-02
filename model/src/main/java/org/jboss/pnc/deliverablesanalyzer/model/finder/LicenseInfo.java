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
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.infinispan.protostream.annotations.ProtoField;

import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LicenseInfo implements Comparable<LicenseInfo>, Serializable {
    @Serial
    private static final long serialVersionUID = 7803402773598522044L;

    @ProtoField(number = 1)
    String comments;

    @ProtoField(number = 2)
    String distribution;

    @ProtoField(number = 3)
    String name;

    @ProtoField(number = 4)
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
     * Implements comparison logic. Order: SPDX ID -> Name -> Source URL -> Distribution -> Comments. Note: 'url' is
     * ignored in comparison.
     */
    @Override
    public int compareTo(LicenseInfo other) {
        if (other == null) {
            return 1;
        }
        return COMPARATOR.compare(this, other);
    }
}
