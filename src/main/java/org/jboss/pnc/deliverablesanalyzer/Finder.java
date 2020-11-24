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

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Stream;

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

    @Inject
    BuildConfig config;

    @Inject
    Provider<DefaultCacheManager> cacheProvider;

    @Inject
    ManagedExecutor pool;

    @Inject
    ClientSession kojiSession;

    @PostConstruct
    public void init() throws IOException {
        if (!config.getDisableCache()) {
            cacheManager = cacheProvider.get();
            LOGGER.info("Initialized cache {}", cacheManager.getName());
        } else {
            LOGGER.info("Cache disabled");
        }
    }

    private static void deletePath(Path path) {
        LOGGER.info("Delete: {}", path);

        try {
            Files.delete(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete path {}", path, e);
        }
    }

    private static boolean cleanup(String directory) {
        return cleanupOutput(directory);
    }

    private static boolean cleanupOutput(String directory) {
        Path outputDirectory = Paths.get(directory);

        try (Stream<Path> stream = Files.walk(outputDirectory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(Finder::deletePath);
        } catch (IOException e) {
            LOGGER.warn("Failed while walking output directory {}", outputDirectory, e);
            return false;
        }

        return true;
    }

    public FinderResult find(
            String id,
            URL url,
            DistributionAnalyzerListener distributionAnalyzerListener,
            BuildFinderListener buildFinderListener,
            BuildConfig specificConfig) throws KojiClientException {

        FinderResult result;

        try {
            List<String> files = Collections.singletonList(url.toExternalForm());

            LOGGER.info(
                    "Starting distribution analysis for {} with config {} and cache manager {}",
                    files,
                    specificConfig,
                    cacheManager != null ? cacheManager.getName() : "disabled");

            DistributionAnalyzer analyzer = new DistributionAnalyzer(files, specificConfig, cacheManager);

            analyzer.setListener(distributionAnalyzerListener);

            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum = pool.submit(analyzer);
            result = findBuilds(id, url, analyzer, futureChecksum, buildFinderListener);

            LOGGER.info("Done finding builds for {}", url);
        } finally {
            boolean isClean = cleanup(config.getOutputDirectory());

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
}
