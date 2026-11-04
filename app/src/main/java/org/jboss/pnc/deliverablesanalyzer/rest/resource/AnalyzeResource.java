/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.rest.resource;

import java.net.URI;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.deliverablesanalyzer.model.AnalyzeResponse;
import org.jboss.pnc.deliverablesanalyzer.rest.AnalyzeEndpoint;
import org.jboss.pnc.deliverablesanalyzer.rest.control.AnalyzeService;

public class AnalyzeResource implements AnalyzeEndpoint {

    @Inject
    AnalyzeService analyzeService;

    @Context
    UriInfo uriInfo;

    @Override
    public Response analyze(AnalyzePayload analyzePayload) {
        String id = analyzeService.analyze(analyzePayload);
        URI cancelUri = uriInfo.getAbsolutePathBuilder().path(id).path("cancel").build();
        return Response.ok(new AnalyzeResponse(id, new Request(Request.Method.POST, cancelUri))).build();
    }

    @Override
    public Response cancel(String id) {
        boolean success = analyzeService.cancel(id);
        return Response.ok(success).build();
    }
}
