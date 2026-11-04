/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import lombok.Data;

@Data
public class BundleLicense {

    private String licenseIdentifier;
    private String link;
    private String description;
}
