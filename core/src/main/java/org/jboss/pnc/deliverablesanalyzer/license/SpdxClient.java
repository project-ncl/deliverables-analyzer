/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.license;

import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.library.LicenseInfoFactory;
import org.spdx.library.model.v3_0_1.expandedlicensing.ListedLicense;

@ApplicationScoped
public class SpdxClient {

    @Retry(maxRetries = 3, delay = 500, delayUnit = ChronoUnit.MILLIS)
    public List<String> getSpdxListedLicenseIds() {
        return LicenseInfoFactory.getSpdxListedLicenseIds();
    }

    @Retry(maxRetries = 3, delay = 500, delayUnit = ChronoUnit.MILLIS)
    public ListedLicense getListedLicenseById(String id) throws InvalidSPDXAnalysisException {
        return LicenseInfoFactory.getListedLicenseById(id);
    }
}
