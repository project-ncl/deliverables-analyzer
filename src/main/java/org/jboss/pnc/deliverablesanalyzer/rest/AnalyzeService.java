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

import java.net.URISyntaxException;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterStyle;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.deliverablesanalyzer.model.AnalyzeResponse;

import jakarta.annotation.security.PermitAll;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/analyze")
public interface AnalyzeService {
    @Operation(summary = "Cancels a running analysis", description = "Cancels a running analysis identified by an ID")
    @APIResponse(responseCode = "200", description = "Analysis was cancelled successfully.")
    @APIResponse(
            responseCode = "404",
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
    @APIResponse(
            responseCode = "200",
            description = "Request accepted.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = AnalyzeResponse.class)

            ))
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
    @Produces(MediaType.APPLICATION_JSON)
    Response analyze(
            @NotNull @Parameter(
                    name = "analyzePayload",
                    description = "Starts an analysis of all the deliverables and performs "
                            + "a callback once the analysis is finished. "
                            + "If heartbeat is specified an HTTP heartbeat will be issued "
                            + "to signal running operation."
                            + "The analysis can be cancelled using the cancel endpoint and the analysis ID, "
                            + "which is returned by this endpoint."
                            + "Users can specify an alternate config for the BuildFinder, which is used "
                            + "as the analysis engine internally."
                            + "The callback is an object AnalysisResult as a JSON.",
                    schema = @Schema(type = SchemaType.OBJECT)) AnalyzePayload analyzePayload)
            throws URISyntaxException;
}
