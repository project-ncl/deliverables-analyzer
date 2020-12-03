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
package org.jboss.pnc.deliverablesanalyzer;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.commons.collections4.MultiValuedMap;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.infinispan.manager.DefaultCacheManager;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.build.finder.core.BuildFinder;
import org.jboss.pnc.build.finder.core.BuildFinderListener;
import org.jboss.pnc.build.finder.core.BuildSystemInteger;
import org.jboss.pnc.build.finder.core.ChecksumType;
import org.jboss.pnc.build.finder.core.DistributionAnalyzer;
import org.jboss.pnc.build.finder.core.DistributionAnalyzerListener;
import org.jboss.pnc.build.finder.koji.ClientSession;
import org.jboss.pnc.build.finder.koji.KojiBuild;
import org.jboss.pnc.build.finder.pnc.client.HashMapCachingPncClient;
import org.jboss.pnc.build.finder.pnc.client.PncClient;
import org.jboss.pnc.deliverablesanalyzer.model.FinderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;

@ApplicationScoped
public class Finder {
    private static final Logger LOGGER = LoggerFactory.getLogger(Finder.class);

    private DefaultCacheManager cacheManager;

    private Map<String, CompletableFuture<List<FinderResult>>> runningOperations = new HashMap<>();

    @Inject
    ManagedExecutor executor;

    @Inject
    BuildConfig config;

    @Inject
    Provider<DefaultCacheManager> cacheProvider;

    @Inject
    ManagedExecutor pool;

    @Inject
    ClientSession kojiSession;

    @Inject
    Cleaner cleaner;

    @PostConstruct
    public void init() {
        if (!config.getDisableCache()) {
            cacheManager = cacheProvider.get();
            LOGGER.info("Initialized cache {}", cacheManager.getName());
        } else {
            LOGGER.info("Cache disabled");
        }
    }

    public boolean cancel(String id) {
        CompletableFuture<List<FinderResult>> future = runningOperations.get(id);

        if (future != null) {
            return future.cancel(true);
        } else {
            return false;
        }
    }

    public List<FinderResult> find(
            String id,
            List<String> urls,
            DistributionAnalyzerListener distributionAnalyzerListener,
            BuildFinderListener buildFinderListener,
            BuildConfig config) throws CancellationException, Throwable {

        CompletableFuture<List<FinderResult>> future = urls.stream().map(url -> CompletableFuture.supplyAsync(() -> {
            try {
                return find(
                        id,
                        URI.create(url).normalize().toURL(),
                        distributionAnalyzerListener,
                        buildFinderListener,
                        config);
            } catch (KojiClientException | MalformedURLException e) {
                throw new CompletionException(e);
            }
        }, executor)).collect(collectingAndThen(toList(), futures -> executeFutures(futures)));
        runningOperations.put(id, future);

        try {
            return future.join();
        } catch (CompletionException e) {
            throw e.getCause();
        }
    }

    /**
     * Ensures, that all passed futures are executed and return is returned in one CompletableFuture once all complete.
     * If a single future fails with an exception the returned future will complete exceptionally.
     *
     * @param initialFutures List of the futures
     * @return A single future combining results of all the futures
     */
    private CompletableFuture<List<FinderResult>> executeFutures(List<CompletableFuture<FinderResult>> initialFutures) {
        CompletableFuture<List<FinderResult>> result = initialFutures.stream()
                .collect(
                        collectingAndThen(
                                toList(),
                                // Creates a new CompletableFuture, which will complete, when all the included futures
                                // are finished
                                // and waits for it to complete
                                futures -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                        .thenApply(
                                                ___ -> futures.stream()
                                                        .map(CompletableFuture::join)
                                                        .collect(Collectors.toList()))));

        // Short circuit the execution if there is an exception thrown in any of the CompletableFutures
        initialFutures.forEach(f -> f.handle((__, e) -> e != null && result.completeExceptionally(e)));
        return result;
    }

    private FinderResult find(
            String id,
            URL url,
            DistributionAnalyzerListener distributionAnalyzerListener,
            BuildFinderListener buildFinderListener,
            BuildConfig config) throws KojiClientException {
        FinderResult result;

        try {
            List<String> files = Collections.singletonList(url.toExternalForm());

            LOGGER.info(
                    "Starting distribution analysis for {} with config {} and cache manager {}",
                    files,
                    config,
                    cacheManager != null ? cacheManager.getName() : "disabled");

            DistributionAnalyzer analyzer = new DistributionAnalyzer(files, config, cacheManager);
            analyzer.setListener(distributionAnalyzerListener);

            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum = pool.submit(analyzer);
            result = findBuilds(id, url, analyzer, futureChecksum, buildFinderListener);

            LOGGER.info("Done finding builds for {}", url);
        } finally {
            int a = 1 + 2;
            // TODO async invocation, ensure it doesn't affect other running analysis on the pod and cleanup
            // in case of cancel
            boolean isClean = cleaner.cleanup(this.config.getOutputDirectory());

            if (isClean) {
                LOGGER.info("Cleanup after finding URL: {}", url);
            } else {
                LOGGER.warn("Cleanup failed after finding URL: {}", url);
            }
        }

        return result;
    }

    private FinderResult findBuilds(
            String id,
            URL url,
            DistributionAnalyzer analyzer,
            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum,
            BuildFinderListener buildFinderListener) throws KojiClientException {

        URL pncURL = config.getPncURL();

        try (PncClient pncClient = pncURL != null ? new HashMapCachingPncClient(config) : null) {
            BuildFinder buildFinder;

            if (pncClient == null) {
                LOGGER.warn("Initializing Build Finder with PNC support disabled because PNC URL is not set");
                buildFinder = new BuildFinder(kojiSession, config, analyzer, cacheManager);
            } else {
                LOGGER.info("Initializing Build Finder PNC client with URL {}", pncURL);
                buildFinder = new BuildFinder(kojiSession, config, analyzer, cacheManager, pncClient);
            }

            buildFinder.setListener(buildFinderListener);

            Future<Map<BuildSystemInteger, KojiBuild>> futureBuilds = pool.submit(buildFinder);

            try {
                Map<ChecksumType, MultiValuedMap<String, String>> checksums = futureChecksum.get();
                Map<BuildSystemInteger, KojiBuild> builds = futureBuilds.get();

                if (LOGGER.isInfoEnabled()) {
                    int size = builds.size();
                    int numBuilds = size >= 1 ? size - 1 : 0;

                    LOGGER.info("Got {} checksum types and {} builds", checksums.size(), numBuilds);
                }

                FinderResult result = new FinderResult(id, url, builds);

                LOGGER.info("Returning result for {}", url);

                return result;
            } catch (ExecutionException e) {
                throw new KojiClientException("Got ExecutionException", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            throw new KojiClientException("Got Exception", e);
        }

        return null;
    }

    /*
     * { // TODO - invoke in parallel, support cancel List<FinderResult> r1 = new ArrayList(); for (String rawUrl :
     * urls) { URL url = URI.create(rawUrl).normalize().toURL(); r1.add(find(id, url, distributionAnalyzerListener,
     * buildFinderListener, config)); } }
     */

    /*
     * { // Issues - throw exception from lambda, support cancel List<CompletableFuture<FinderResult>> futureResults =
     * new ArrayList(); for (String rawUrl : urls) { futureResults.add(executor.supplyAsync(() -> { URL url =
     * URI.create(rawUrl).normalize().toURL(); return find(id, url, distributionAnalyzerListener, buildFinderListener,
     * config); })); }
     *
     * for (CompletableFuture<FinderResult> future : futureResults) { try { results.add(future.get()); } catch
     * (InterruptedException | ExecutionException e) { e.printStackTrace(); } } }
     */
}
