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
