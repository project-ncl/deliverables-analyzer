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
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.security.PermitAll;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.hibernate.validator.constraints.URL;
import org.jboss.pnc.deliverablesanalyzer.Finder;
import org.jboss.pnc.deliverablesanalyzer.ResultCache;
import org.jboss.pnc.deliverablesanalyzer.model.FinderResult;
import org.jboss.resteasy.annotations.jaxrs.FormParam;
import org.jboss.resteasy.annotations.jaxrs.PathParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;

@ApplicationScoped
@Path("analyze")
public class AnalyzeResource implements AnalyzeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeResource.class);

    @ConfigProperty(name = "analyze.results.timeout", defaultValue = "290000")
    Long timeout;

    @Inject
    ManagedExecutor pool;

    @Inject
    ResultCache<String, CompletionStage<FinderResult>> results;

    @Inject
    Finder finder;

    @Context
    UriInfo uriInfo;

    @Override
    @Operation(description = "Get the build config")
    @APIResponse(responseCode = "200", description = "Result OK")
    public Response config() {
        return Response.ok().entity(finder.getBuildConfig()).build();
    }

    @Override
    @Operation(description = "Get a result")
    @APIResponse(responseCode = "200", description = "Result OK")
    @APIResponse(responseCode = "404", description = "Result not found")
    @APIResponse(responseCode = "500", description = "Error getting result")
    @APIResponse(responseCode = "503", description = "Timeout getting result. Try again later")
    @Parameter(name = "id", description = "Result identifier", required = true)
    @GET
    @Path("results/{id}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response results(@NotNull @Pattern(regexp = "^[a-f0-9]{8}$") @PathParam String id) {
        CompletionStage<FinderResult> futureResult = results.get(id);

        if (futureResult == null) {
            LOGGER.info("Result id {} is null. Returning Not Found", id);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        LOGGER.info("Result id {} is {}", id, futureResult);

        CompletableFuture<FinderResult> completableFuture = futureResult.toCompletableFuture();

        if (completableFuture.isCancelled() || completableFuture.isCompletedExceptionally()) {
            LOGGER.info("Removing abnormal result id {} from cache so that it can be submitted again", id);
            results.remove(id);
            LOGGER.info("Result id {} is cancelled or completed exceptionally. Returning Server Error", id);
            return Response.serverError().build();
        }

        if (completableFuture.isDone()) {
            try {
                LOGGER.info("Result id {} is done", id);
                FinderResult result = completableFuture.get();
                return Response.ok(result).build();
            } catch (InterruptedException e) {
                LOGGER.info("Result id {} is done, but was interrupted. Returning Server Error", id);
                Thread.currentThread().interrupt();
                throw new InternalServerErrorException(e);
            } catch (ExecutionException e) {
                LOGGER.info("Result id {} is done, but had exception thrown. Returning Server Error", id);
                throw new InternalServerErrorException(e);
            }
        } else {
            try {
                LOGGER.info("Result id {} is not done yet. Waiting at most {} ms", id, timeout);
                FinderResult result = completableFuture.get(timeout, TimeUnit.MILLISECONDS);
                return Response.ok(result).build();
            } catch (InterruptedException e) {
                LOGGER.info("Result id {} was interrupted. Returning Server Error", id);
                Thread.currentThread().interrupt();
                throw new InternalServerErrorException(e);
            } catch (ExecutionException e) {
                LOGGER.info("Result id {} had exception thrown. Returning Server Error", id);
                throw new InternalServerErrorException(e);
            } catch (TimeoutException e) {
                LOGGER.info("Result id {} timed out. Returning Service Unavailable", id);
                throw new ServiceUnavailableException(Duration.ofMinutes(5L).getSeconds(), e);
            }
        }
    }

    @Override
    @Operation(description = "Analyze a URL")
    @APIResponse(responseCode = "201", description = "Created")
    @APIResponse(responseCode = "400", description = "Bad URL protocol or syntax")
    @APIResponse(responseCode = "500", description = "Error during find")
    @Parameter(name = "url", description = "The URL of the file to analyze", required = true)
    @POST
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze(@NotNull @FormParam @URL(regexp = "^http(s)?:.*") String url) {
        URI uri = URI.create(url).normalize();
        String normalizedUrl = uri.toString();
        // XXX: Hash URL instead of file contents so that we don't have to download the file
        String sha256 = DigestUtils.sha256Hex(normalizedUrl);
        String id = sha256.substring(0, 8);

        results.computeIfAbsent(id, k -> pool.supplyAsync(() -> {
            try {
                return finder.find(uri.toURL());
            } catch (IOException | KojiClientException e) {
                throw new InternalServerErrorException(e);
            }
        }));

        String location = uriInfo.getAbsolutePathBuilder()
                .path("results")
                .path("{id}")
                .resolveTemplate("id", id)
                .toTemplate();

        return Response.seeOther(URI.create(location).normalize()).build();
    }
}
