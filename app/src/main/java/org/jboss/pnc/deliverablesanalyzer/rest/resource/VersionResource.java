/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.rest.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.jboss.pnc.deliverablesanalyzer.rest.VersionEndpoint;
import org.jboss.pnc.deliverablesanalyzer.rest.control.VersionService;

public class VersionResource implements VersionEndpoint {

    @Inject
    VersionService versionService;

    @Override
    public Response getVersion() {
        return Response.ok(versionService.getComponentVersion()).build();
    }
}
