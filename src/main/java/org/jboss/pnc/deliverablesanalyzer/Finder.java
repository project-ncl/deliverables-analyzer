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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.io.FileUtils;
import org.eclipse.microprofile.config.ConfigProvider;
import org.infinispan.commons.util.Version;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.jboss.marshalling.commons.GenericJBossMarshaller;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.build.finder.core.BuildFinder;
import org.jboss.pnc.build.finder.core.BuildSystemInteger;
import org.jboss.pnc.build.finder.core.ChecksumType;
import org.jboss.pnc.build.finder.core.ConfigDefaults;
import org.jboss.pnc.build.finder.core.DistributionAnalyzer;
import org.jboss.pnc.build.finder.core.Utils;
import org.jboss.pnc.build.finder.koji.KojiBuild;
import org.jboss.pnc.build.finder.koji.KojiClientSession;
import org.jboss.pnc.build.finder.pnc.client.HashMapCachingPncClient;
import org.jboss.pnc.build.finder.pnc.client.PncClient;
import org.jboss.pnc.deliverablesanalyzer.model.FinderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;

public class Finder {
    private static final Logger LOGGER = LoggerFactory.getLogger(Finder.class);

    private final File configFile = new File(ConfigDefaults.CONFIG);

    private DefaultCacheManager cacheManager;

    private BuildConfig config;

    private Optional<String> optionalKojiHubURL = ConfigProvider.getConfig()
            .getOptionalValue("koji.hub.url", String.class);

    private Optional<String> optionalPncURL = ConfigProvider.getConfig().getOptionalValue("pnc.url", String.class);

    private void ensureConfigurationDirectoryExists() throws IOException {
        Path configPath = Paths.get(ConfigDefaults.CONFIG_PATH);

        LOGGER.info("Configuration directory is: {}", configPath);

        if (Files.exists(configPath)) {
            if (!Files.isDirectory(configPath)) {
                throw new IOException("Configuration directory is not a directory: " + configPath);
            }
        } else {
            LOGGER.info("Creating configuration directory: {}", configPath);

            Files.createDirectory(configPath);
        }
    }

    private BuildConfig setupBuildConfig() throws IOException {
        BuildConfig defaults = BuildConfig.load(Finder.class.getClassLoader());

        if (configFile.exists()) {
            if (defaults == null) {
                config = BuildConfig.load(configFile);
            } else {
                config = BuildConfig.merge(defaults, configFile);
            }
        } else {
            if (defaults == null) {
                config = new BuildConfig();
            } else {
                config = defaults;
            }
        }

        // XXX: Force output directory since it defaults to "." which usually isn't the best
        Path tmpDir = Files.createTempDirectory("deliverables-analyzer-");

        config.setOutputDirectory(tmpDir.toAbsolutePath().toString());

        LOGGER.info("Output directory set to: {}", config.getOutputDirectory());

        return config;
    }

    private static void deletePath(Path path) {
        LOGGER.info("Delete: {}", path);
        try {
            Files.delete(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete path {}", path, e);
        }
    }

    @SuppressWarnings("deprecation")
    private void initCaches(BuildConfig config) throws IOException {
        ensureConfigurationDirectoryExists();

        Path locationPath = Paths.get(ConfigDefaults.CONFIG_PATH, "cache");
        String location = locationPath.toAbsolutePath().toString();

        LOGGER.info("Cache location is: {}", location);

        if (!Files.exists(locationPath)) {
            Files.createDirectory(locationPath);
        }

        if (!Files.isDirectory(locationPath)) {
            throw new IOException("Tried to set cache location to non-directory: " + locationPath);
        }

        if (!Files.isReadable(locationPath)) {
            throw new IOException("Cache location is not readable: " + locationPath);
        }

        if (!Files.isWritable(locationPath)) {
            throw new IOException("Cache location is not writable: " + locationPath);
        }

        KojiBuild.KojiBuildExternalizer externalizer = new KojiBuild.KojiBuildExternalizer();
        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();

        globalConfig.globalState()
                .persistentLocation(location)
                .serialization()
                .marshaller(new GenericJBossMarshaller())
                .addAdvancedExternalizer(externalizer.getId(), externalizer)
                .whiteList()
                .addRegexp(".*")
                .create();

        Configuration configuration = new ConfigurationBuilder().expiration()
                .lifespan(config.getCacheLifespan())
                .maxIdle(config.getCacheMaxIdle())
                .wakeUpInterval(-1L)
                .persistence()
                .passivation(false)
                .addSingleFileStore()
                .segmented(true)
                .shared(false)
                .preload(true)
                .fetchPersistentState(true)
                .purgeOnStartup(false)
                .location(location)
                .build();

        Set<ChecksumType> checksumTypes = config.getChecksumTypes();
        GlobalConfiguration globalConfiguration = globalConfig.build();
        cacheManager = new DefaultCacheManager(globalConfiguration);

        LOGGER.info("Setting up caches for checksum types size: {}", checksumTypes.size());

        for (ChecksumType checksumType : checksumTypes) {
            cacheManager.defineConfiguration("files-" + checksumType, configuration);
            cacheManager.defineConfiguration("checksums-" + checksumType, configuration);
            cacheManager.defineConfiguration("checksums-pnc-" + checksumType, configuration);
            cacheManager.defineConfiguration("rpms-" + checksumType, configuration);
        }

        cacheManager.defineConfiguration("builds", configuration);
        cacheManager.defineConfiguration("builds-pnc", configuration);
    }

    private boolean cleanup(String directory, EmbeddedCacheManager cacheManager, ExecutorService pool) {
        boolean success = cleanupOutput(directory);

        success |= cleanupCache(cacheManager);
        success |= cleanupPool(pool);

        return success;
    }

    private boolean cleanupOutput(String directory) {
        Path outputDirectory = Paths.get(directory);

        try (Stream<Path> stream = Files.walk(outputDirectory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(Finder::deletePath);
        } catch (IOException e) {
            LOGGER.warn("Failed while walking output directory {}", outputDirectory, e);
            return false;
        }

        return true;
    }

    private boolean cleanupCache(EmbeddedCacheManager cacheManager) {
        if (cacheManager != null) {
            try {
                cacheManager.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close cache manager {}", cacheManager.getCache().getName(), e);
                return false;
            }
        }

        return true;
    }

    private boolean cleanupPool(ExecutorService pool) {
        if (pool != null) {
            Utils.shutdownAndAwaitTermination(pool);
        }

        return true;
    }

    public FinderResult find(URL url) throws IOException, KojiClientException {
        FinderResult result;
        ExecutorService pool = null;

        config = setupBuildConfig();

        try {
            Path filename = Paths.get(url.getPath()).getFileName();
            File file = Paths.get(config.getOutputDirectory()).resolve(filename).toFile();

            LOGGER.info("Copying {} to {}", url, file);

            FileUtils.copyURLToFile(url, file);

            List<File> inputs = Collections.singletonList(file);

            if (cacheManager == null && !config.getDisableCache()) {
                initCaches(config);
                LOGGER.info(
                        "Initialized {} {} cache {}",
                        Version.getBrandName(),
                        Version.getVersion(),
                        cacheManager.getName());
            } else {
                LOGGER.info("Cache disabled");
            }

            int nThreads = 1 + config.getChecksumTypes().size();

            LOGGER.info("Setting up fixed thread pool of size: {}", nThreads);

            pool = Executors.newFixedThreadPool(nThreads);

            DistributionAnalyzer analyzer = new DistributionAnalyzer(inputs, config, cacheManager);

            LOGGER.info(
                    "Starting distribution analysis for {} with config {} and cache manager {}",
                    inputs,
                    config,
                    cacheManager.getName());

            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum = pool.submit(analyzer);
            result = findBuilds(analyzer, pool, futureChecksum);
        } finally {
            LOGGER.info("Cleanup after finding {}", url);
            cleanup(config.getOutputDirectory(), cacheManager, pool);
        }

        return result;
    }

    private FinderResult findBuilds(
            DistributionAnalyzer analyzer,
            ExecutorService pool,
            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum) throws KojiClientException {
        URL kojiHubURL;

        if (optionalKojiHubURL.isPresent()) {
            try {
                kojiHubURL = new URL(optionalKojiHubURL.get());
            } catch (MalformedURLException e) {
                throw new KojiClientException("Bad Koji hub URL: " + optionalKojiHubURL.get(), e);
            }
        } else {
            kojiHubURL = config.getKojiHubURL();
        }

        LOGGER.info("Koji Hub URL: {}", kojiHubURL);

        if (kojiHubURL == null) {
            throw new KojiClientException("Koji hub URL is not set");
        }

        try (KojiClientSession session = new KojiClientSession(kojiHubURL)) {
            LOGGER.info("Initialized Koji client session with URL {}", kojiHubURL);

            BuildFinder finder;
            URL pncURL;

            if (optionalPncURL.isPresent()) {
                try {
                    pncURL = new URL(optionalPncURL.get());
                } catch (MalformedURLException e) {
                    throw new KojiClientException("Bad PNC URL: " + optionalPncURL.get(), e);
                }
            } else {
                pncURL = config.getPncURL();
            }

            LOGGER.info("PNC URL: {}", pncURL);

            if (pncURL == null) {
                LOGGER.warn("PNC support disabled because PNC URL is not set");
                finder = new BuildFinder(session, config, analyzer, cacheManager);
            } else {
                PncClient pncclient = new HashMapCachingPncClient(config);
                LOGGER.info("Initialized PNC client with URL {}", pncURL);
                finder = new BuildFinder(session, config, analyzer, cacheManager, pncclient);
            }

            LOGGER.info("Initialized finder");

            Future<Map<BuildSystemInteger, KojiBuild>> futureBuilds = pool.submit(finder);

            try {
                Map<ChecksumType, MultiValuedMap<String, String>> checksums = futureChecksum.get();
                Map<BuildSystemInteger, KojiBuild> builds = futureBuilds.get();

                LOGGER.info("Got {} checksum types and {} builds", checksums.size(), builds.size() - 1);

                FinderResult result = new FinderResult(builds);

                LOGGER.info("Initialized finder result");

                return result;
            } catch (ExecutionException e) {
                throw new KojiClientException("Got ExecutionException", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return null;
    }

    public BuildConfig getBuildConfig() {
        return config;
    }
}
