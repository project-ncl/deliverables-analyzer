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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.BuildConfigCache;
import org.jboss.pnc.deliverablesanalyzer.Finder;
import org.jboss.pnc.deliverablesanalyzer.ResultCache;
import org.jboss.pnc.deliverablesanalyzer.StatusCache;
import org.jboss.pnc.deliverablesanalyzer.model.FinderResult;
import org.jboss.pnc.deliverablesanalyzer.model.FinderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;

@ApplicationScoped
public class AnalyzeResource implements AnalyzeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeResource.class);

    @ConfigProperty(name = "analyze.results.timeout", defaultValue = "3590000")
    Long timeout;

    @Inject
    ManagedExecutor pool;

    @Inject
    BuildConfigCache<String, BuildConfig> configs;

    @Inject
    ResultCache<String, CompletionStage<FinderResult>> results;

    @Inject
    StatusCache<String, FinderStatus> statuses;

    @Inject
    Finder finder;

    @Inject
    BuildConfig applicationConfig;

    @Context
    UriInfo uriInfo;

    @Override
    public BuildConfig configs(String id) {
        BuildConfig config = configs.get(id);

        if (config == null) {
            LOGGER.info("Config id {} is null. Returning Not Found", id);
            throw new NotFoundException("Config id " + id + " not found");
        }

        return config;
    }

    @Override
    public FinderStatus statuses(String id) {
        FinderStatus status = statuses.get(id);

        if (status == null) {
            LOGGER.info("Status id {} is null. Returning Not Found", id);
            throw new NotFoundException("Status id " + id + " not found");
        }

        return status;
    }

    @Override
    public FinderResult results(String id) {
        var futureResult = results.get(id);

        if (futureResult == null) {
            LOGGER.info("Result id {} is null. Returning Not Found", id);
            throw new NotFoundException("Result id " + id + " not found");
        }

        LOGGER.info("Result id {} is {}", id, futureResult);

        CompletableFuture<FinderResult> completableFuture = futureResult.toCompletableFuture();

        if (completableFuture.isCancelled() || completableFuture.isCompletedExceptionally()) {
            LOGGER.info("Removing abnormal result id {} from cache so that it can be submitted again", id);
            results.remove(id);
            configs.remove(id);
            statuses.remove(id);
            LOGGER.info("Result id {} is cancelled or completed exceptionally. Returning Server Error", id);
            throw new InternalServerErrorException("Result id " + id + " was cancelled or completed exceptionally");
        }

        if (completableFuture.isDone()) {
            try {
                LOGGER.info("Result id {} is done", id);
                return completableFuture.get();
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
                return completableFuture.get(timeout, TimeUnit.MILLISECONDS);
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
    public Response analyze(String url, String config) {
        URI uri = URI.create(url).normalize();
        String normalizedUrl = uri.toString();
        // XXX: Hash URL instead of file contents so that we don't have to download the file
        String sha256 = DigestUtils.sha256Hex(normalizedUrl);
        String id = sha256.substring(0, 8);

        try {
            BuildConfig specificConfig = BuildConfig.copy(applicationConfig);
            if (config != null) {
                BuildConfig config2 = BuildConfig.load(config);
                if (config2.getExcludes() != null) {
                    specificConfig.setExcludes(config2.getExcludes());
                }

                if (config2.getArchiveExtensions() != null) {
                    specificConfig.setArchiveExtensions(config2.getArchiveExtensions());
                }

                if (config2.getArchiveTypes() != null) {
                    specificConfig.setArchiveTypes(config2.getArchiveTypes());
                }
            }

            results.computeIfAbsent(id, k -> pool.supplyAsync(() -> {
                configs.putIfAbsent(id, specificConfig);

                FinderStatus status = new FinderStatus();

                statuses.putIfAbsent(id, status);

                try {
                    return finder.find(id, uri.toURL(), status, status, specificConfig);
                } catch (IOException | KojiClientException e) {
                    throw new InternalServerErrorException(e);
                }
            }));
        } catch (IOException e) {
            throw new InternalServerErrorException(e);
        }

        var location = uriInfo.getAbsolutePathBuilder()
                .path("results")
                .path("{id}")
                .resolveTemplate("id", id)
                .toTemplate();

        return Response.created(URI.create(location).normalize()).entity(id).build();
    }
}
