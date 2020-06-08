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
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.pnc.deliverablesanalyzer.Version;

@Path("version")
@ApplicationScoped
public class VersionResource implements VersionService {
    @Override
    @Operation(summary = "Display version information", description = "Display version information.")
    @APIResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(
                    mediaType = MediaType.TEXT_PLAIN,
                    schema = @Schema(type = SchemaType.STRING, implementation = String.class)))
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @PermitAll
    public String getVersion() {
        return Version.getVersion();
    }
}
