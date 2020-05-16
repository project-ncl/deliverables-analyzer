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

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletionStage;

import javax.annotation.security.PermitAll;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.pnc.deliverablesanalyzer.Finder;
import org.jboss.pnc.deliverablesanalyzer.model.FinderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;

import io.quarkus.security.identity.SecurityIdentity;

@ApplicationScoped
@Path("analyze")
public class AnalyzeResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeResource.class);

    @Inject
    @RequestScoped
    SecurityIdentity identity;

    @Inject
    ManagedExecutor pool;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @Operation(
            summary = "Analyzes file at the given URL",
            description = "Reads the file from the given URL, scans it, and stores the data in the database.")
    @Parameter(name = "url", description = "URL to the file to analyze.", required = true)
    @APIResponse(responseCode = "200", description = "Analysis successful.")
    @APIResponse(responseCode = "500", description = "Analysis error.")
    public CompletionStage<FinderResult> analyze(@NotNull @QueryParam("url") URL url) {
        final String username = identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName();

        LOGGER.info("Analyzing {} for {}", url, username);

        return pool.supplyAsync(() -> {
            try {
                FinderResult result = new Finder().find(url);
                LOGGER.info("Done analyzing {} for {}", url, username);
                return result;
            } catch (IOException | KojiClientException e) {
                throw new WebApplicationException(e);
            }
        });
    }
}
