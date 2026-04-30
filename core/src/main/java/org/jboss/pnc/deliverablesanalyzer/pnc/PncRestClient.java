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
package org.jboss.pnc.deliverablesanalyzer.pnc;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.BuildPushReport;
import org.jboss.pnc.dto.ProductMilestone;
import org.jboss.pnc.dto.ProductVersion;
import org.jboss.pnc.dto.response.Page;

@Path("/pnc-rest/v2")
@RegisterRestClient(configKey = "pnc-api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface PncRestClient {

    @GET
    @Path("/artifacts")
    Page<Artifact> getArtifacts(@QueryParam("sha256") String sha256, @QueryParam("q") String q);

    @GET
    @Path("/build-pushes/{id}")
    BuildPushReport getBuildPushReport(@PathParam("id") String buildId);

    @GET
    @Path("/product-milestones/{id}")
    ProductMilestone getProductMilestone(@PathParam("id") String id);

    @GET
    @Path("/product-versions/{id}")
    ProductVersion getProductVersion(@PathParam("id") String id);

}
