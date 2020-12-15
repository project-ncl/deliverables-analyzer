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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
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

    private Map<String, CancelWrapper> runningOperations = new HashMap<>();

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
        CancelWrapper cancelWrapper = runningOperations.get(id);

        if (cancelWrapper != null) {
            cancelWrapper.cancel();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Executes analysis of the provided archives identified by URLs, which must be downloadable using HTTP(S). The
     * operation is executed synchronously, but the analysis itself runs several executors in parallel.
     *
     * @param id ID of the analysis
     * @param urls List of URLs
     * @param distributionAnalyzerListener A listener for events from DistributionAnalyzer
     * @param buildFinderListener A listener for events from Buildfinder
     * @param config Configuration of the analysis
     * @return Results of the analysis if the whole operation was successful. Partial fail results in fail of the whole
     *         analysis.
     * @throws CancellationException Thrown in case of cancel operation performed during the analysis
     * @throws Throwable Thrown in case of any errors during the analysis
     */
    public List<FinderResult> find(
            String id,
            List<String> urls,
            DistributionAnalyzerListener distributionAnalyzerListener,
            BuildFinderListener buildFinderListener,
            BuildConfig config) throws CancellationException, Throwable {

        List<Future<FinderResult>> submittedTasks = urls.stream().map(url -> executor.submit(() -> {
            LOGGER.debug("Analysis of URL " + url + " started.");
            try {
                FinderResult result = find(
                        id,
                        URI.create(url).normalize().toURL(),
                        distributionAnalyzerListener,
                        buildFinderListener,
                        config);
                LOGGER.debug("Analysis of URL " + url + " finished.");
                return result;
            } catch (KojiClientException | MalformedURLException e) {
                throw new ExecutionException(e);
            }
        })).collect(Collectors.toList());

        CancelWrapper cancelWrapper = new CancelWrapper();
        runningOperations.put(id, cancelWrapper);

        try {
            return awaitResults(submittedTasks, cancelWrapper);
        } catch (CancellationException e) {
            LOGGER.info("Analysis " + id + " was cancelled", e);
            throw e;
        } catch (ExecutionException e) {
            LOGGER.info("Analysis " + id + " failed due to ", e);
            throw e.getCause();
        }
    }

    private List<FinderResult> awaitResults(List<Future<FinderResult>> submittedTasks, CancelWrapper cancelWrapper)
            throws CancellationException, ExecutionException {
        List<FinderResult> results = new ArrayList<>();

        int total = submittedTasks.size();
        int done = 0;
        Iterator<Future<FinderResult>> it = submittedTasks.iterator();

        while (done < total) {
            while (it.hasNext()) {
                try {
                    Future<FinderResult> futureTask = it.next();
                    if (futureTask.isDone()) {
                        it.remove();
                        results.add(futureTask.get());
                        done++;
                    } else {
                        Thread.sleep(500);
                    }
                } catch (InterruptedException e) {
                    LOGGER.warn("Sleeping while awaiting results was interrupted", e);
                }
            }
            it = submittedTasks.iterator();

            if (cancelWrapper.isCancelled()) {
                LOGGER.info("Cancelling all remaining tasks: " + submittedTasks.size());
                it.forEachRemaining(f -> f.cancel(true));
                LOGGER.info("All remaining tasks were cancelled");
                throw new CancellationException("Operation was cancelled manually");
            }
        }

        return results;
    }

    private FinderResult find(
            String id,
            URL url,
            DistributionAnalyzerListener distributionAnalyzerListener,
            BuildFinderListener buildFinderListener,
            BuildConfig config) throws KojiClientException {
        FinderResult result;

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

        // TODO fix cleanup. It must:
        // - support cancel
        // - not affect running operations
        // - run async
        // Consider using dir/id{8}-urlHash{8}
        /*
         * boolean isClean = cleaner.cleanup(this.config.getOutputDirectory());
         *
         * if (isClean) { LOGGER.info("Cleanup after finding URL: {}", url); } else {
         * LOGGER.warn("Cleanup failed after finding URL: {}", url); }
         */

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

    private class CancelWrapper {
        private boolean cancelled = false;

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
