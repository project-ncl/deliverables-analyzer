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

import javax.annotation.security.PermitAll;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterStyle;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.hibernate.validator.constraints.URL;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.model.FinderResult;
import org.jboss.pnc.deliverablesanalyzer.model.FinderStatus;
import org.jboss.resteasy.annotations.jaxrs.FormParam;
import org.jboss.resteasy.annotations.jaxrs.PathParam;

@Path("analyze")
public interface AnalyzeService {
    @Operation(summary = "Get build config", description = "Get build config.")
    @APIResponse(
            responseCode = "200",
            description = "Got current build config",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.OBJECT, implementation = BuildConfig.class)))
    @APIResponse(
            responseCode = "404",
            description = "Config not found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @GET
    @Path("configs/{id}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    BuildConfig configs(
            @NotEmpty @Parameter(
                    name = "id",
                    description = "Config identifier",
                    schema = @Schema(type = SchemaType.STRING),
                    required = true,
                    style = ParameterStyle.SIMPLE) @Pattern(regexp = "^[a-f0-9]{8}$") @PathParam String id);

    @Operation(summary = "Get result", description = "Get result.")
    @APIResponse(
            responseCode = "200",
            description = "Result OK",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.OBJECT, implementation = FinderResult.class)))
    @APIResponse(
            responseCode = "404",
            description = "Result not found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @APIResponse(
            responseCode = "500",
            description = "Error getting result.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @APIResponse(
            responseCode = "503",
            description = "Timeout getting result. Try again later.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @GET
    @Path("results/{id}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    FinderResult results(
            @NotEmpty @Parameter(
                    name = "id",
                    description = "Result identifier",
                    schema = @Schema(type = SchemaType.STRING),
                    required = true,
                    style = ParameterStyle.SIMPLE) @Pattern(regexp = "^[a-f0-9]{8}$") @PathParam String id);

    @Operation(summary = "Get status", description = "Get status.")
    @APIResponse(
            responseCode = "200",
            description = "Got current status",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.OBJECT, implementation = BuildConfig.class)))
    @APIResponse(
            responseCode = "404",
            description = "Status not found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @GET
    @Path("statuses/{id}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    FinderStatus statuses(
            @NotEmpty @Parameter(
                    name = "id",
                    description = "Status identifier",
                    schema = @Schema(type = SchemaType.STRING),
                    required = true,
                    style = ParameterStyle.SIMPLE) @Pattern(regexp = "^[a-f0-9]{8}$") @PathParam String id);

    @Operation(summary = "Analyze a URL", description = "Analyze a URL.")
    @APIResponse(
            responseCode = "201",
            description = "Created.",
            headers = @Header(
                    name = "Location",
                    description = "URL containing result generated by this request.",
                    schema = @Schema(type = SchemaType.STRING),
                    required = true))
    @APIResponse(
            responseCode = "400",
            description = "Bad URL protocol or syntax.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @APIResponse(
            responseCode = "500",
            description = "Error during find.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ErrorMessage.class)))
    @Parameter(
            name = "url",
            description = "The URL of the file to analyze",
            schema = @Schema(type = SchemaType.STRING),
            required = true,
            style = ParameterStyle.FORM)
    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    Response analyze(
            @NotEmpty @FormParam @Parameter(
                    name = "url",
                    description = "URL to analyze",
                    schema = @Schema(type = SchemaType.STRING),
                    required = true,
                    style = ParameterStyle.SIMPLE) @URL(regexp = "^http(s)?:.*") String url,
            @FormParam @Parameter(
                    name = "config",
                    description = "Build config",
                    schema = @Schema(type = SchemaType.STRING),
                    required = true,
                    style = ParameterStyle.SIMPLE) String config);
}
