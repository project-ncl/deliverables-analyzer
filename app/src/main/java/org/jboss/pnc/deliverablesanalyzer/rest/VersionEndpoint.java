/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.pnc.api.dto.ComponentVersion;

@Path("/version")
public interface VersionEndpoint {

    @Operation(
            summary = "Gets the component version.",
            description = "Retrieves the version information for the Deliverables Analyzer.")
    @APIResponse(
            responseCode = "200",
            description = "Successfully retrieved the version.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ComponentVersion.class)))
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/")
    Response getVersion();
}
