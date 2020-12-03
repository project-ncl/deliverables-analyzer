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
package org.jboss.pnc.deliverablesanalyzer.rest;

import java.util.List;

import javax.annotation.security.PermitAll;
import javax.validation.constraints.NotEmpty;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterStyle;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.pnc.api.dto.Request;

@Path("analyze")
public interface AnalyzeService {
    @Operation(summary = "Cancels a running analysis", description = "Cancels a running analysis identified by an ID")
    @APIResponse(responseCode = "200", description = "Analysis was cancelled successfully.")
    @APIResponse(
            responseCode = "400",
            description = "No running analysis with the provided ID was not found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @APIResponse(
            responseCode = "500",
            description = "Error happened when cancelling the operation.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @POST
    @Path("{id}/cancel")
    @PermitAll
    Response cancel(
            @PathParam("id") @NotEmpty @Parameter(
                    name = "id",
                    description = "ID of the running analysis",
                    schema = @Schema(type = SchemaType.STRING),
                    required = true,
                    style = ParameterStyle.SIMPLE) String id);

    @Operation(
            summary = "Analyze a list of deliverables and perform a callback when the analysis is finished.",
            description = "Analyze a list of deliverables and perform a callback when the analysis is finished. "
                    + "During the analysis a regular hearth beat callback is performed if the parameter is specified."
                    + "The endpoint returns a String ID, which can be used to cancel the operation.")
    @APIResponse(responseCode = "200", description = "Request accepted.")
    @APIResponse(
            responseCode = "400",
            description = "Bad request parameters.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @APIResponse(
            responseCode = "500",
            description = "Error happened when initializing the analysis.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    Response analyze(
            @NotEmpty @Parameter(
                    name = "urls",
                    description = "List of URLs to deliverables to analyze",
                    schema = @Schema(type = SchemaType.OBJECT),
                    required = true,
                    style = ParameterStyle.SIMPLE) List<String> urls,
            @Parameter(
                    name = "config",
                    description = "Optional configuration of the analyzer in JSON format",
                    schema = @Schema(type = SchemaType.STRING),
                    style = ParameterStyle.SIMPLE) String config,
            @NotEmpty @Parameter(
                    name = "callback",
                    description = "Callback will be be performed once the analysis is finished",
                    schema = @Schema(type = SchemaType.OBJECT),
                    required = true,
                    style = ParameterStyle.SIMPLE) Request callback,
            @Parameter(
                    name = "heartbeat",
                    description = "If specified a heartbeat will be initiated in regular intervals",
                    schema = @Schema(type = SchemaType.OBJECT),
                    style = ParameterStyle.SIMPLE) Request heartbeat);
}
