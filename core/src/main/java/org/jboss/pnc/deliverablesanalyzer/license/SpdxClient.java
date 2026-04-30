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
