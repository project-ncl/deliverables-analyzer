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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.apache.commons.collections4.MultiValuedMap;
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
import org.jboss.pnc.build.finder.core.BuildFinderListener;
import org.jboss.pnc.build.finder.core.BuildSystemInteger;
import org.jboss.pnc.build.finder.core.ChecksumType;
import org.jboss.pnc.build.finder.core.ConfigDefaults;
import org.jboss.pnc.build.finder.core.DistributionAnalyzer;
import org.jboss.pnc.build.finder.core.DistributionAnalyzerListener;
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

    public Finder() throws IOException {
        config = setupBuildConfig();
    }

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

    private void setKojiHubURL(BuildConfig config) throws IOException {
        Optional<String> optionalKojiHubURL = ConfigProvider.getConfig().getOptionalValue("koji.hub.url", String.class);

        if (optionalKojiHubURL.isPresent()) {
            String s = optionalKojiHubURL.get();

            try {
                URL kojiHubURL = new URL(s);
                config.setKojiHubURL(kojiHubURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad Koji hub URL: " + s, e);
            }
        }
    }

    private void setKojiWebURL(BuildConfig config) throws IOException {
        Optional<String> optionalKojiWebURL = ConfigProvider.getConfig().getOptionalValue("koji.web.url", String.class);

        if (optionalKojiWebURL.isPresent()) {
            String s = optionalKojiWebURL.get();

            try {
                URL kojiWebURL = new URL(s);
                config.setKojiWebURL(kojiWebURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad Koji web URL: " + s, e);
            }
        } else if (config.getKojiWebURL() == null && config.getKojiHubURL() != null) {
            // XXX: hack for missing koji.web.url
            String s = config.getKojiHubURL().toExternalForm().replace("hub.", "web.").replace("hub", "");

            try {
                URL kojiWebURL = new URL(s);
                config.setKojiWebURL(kojiWebURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad Koji web URL: " + s, e);
            }
        }
    }

    private void setPncURL(BuildConfig config) throws IOException {
        Optional<String> optionalPncURL = ConfigProvider.getConfig().getOptionalValue("pnc.url", String.class);

        if (optionalPncURL.isPresent()) {
            String s = optionalPncURL.get();

            try {
                URL pncURL = new URL(s);
                config.setPncURL(pncURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad PNC URL: " + s, e);
            }
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
            config = Objects.requireNonNullElse(defaults, new BuildConfig());
        }

        setKojiHubURL(config);
        setKojiWebURL(config);
        setPncURL(config);

        // XXX: Force output directory since it defaults to "." which usually isn't the best
        Path tmpDir = Files.createTempDirectory("deliverables-analyzer-");

        config.setOutputDirectory(tmpDir.toAbsolutePath().toString());

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

    public FinderResult find(
            String id,
            URL url,
            DistributionAnalyzerListener distributionAnalyzerListener,
            BuildFinderListener buildFinderListener) throws IOException, KojiClientException {
        FinderResult result;
        ExecutorService pool = null;

        try {
            if (cacheManager == null && !config.getDisableCache()) {
                LOGGER.info("Initializing {} {} cache", Version.getBrandName(), Version.getVersion());

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

            List<String> files = Collections.singletonList(url.toExternalForm());

            LOGGER.info(
                    "Starting distribution analysis for {} with config {} and cache manager {}",
                    files,
                    config,
                    cacheManager != null ? cacheManager.getName() : "disabled");

            DistributionAnalyzer analyzer = new DistributionAnalyzer(files, config, cacheManager);

            analyzer.setListener(distributionAnalyzerListener);

            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum = pool.submit(analyzer);
            result = findBuilds(id, url, analyzer, pool, futureChecksum, buildFinderListener);

            LOGGER.info("Done finding builds for {}", url);
        } finally {
            boolean isClean = cleanup(config.getOutputDirectory(), cacheManager, pool);

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
            ExecutorService pool,
            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum,
            BuildFinderListener buildFinderListener) throws KojiClientException {
        URL kojiHubURL = config.getKojiHubURL();

        LOGGER.info("Koji Hub URL: {}", kojiHubURL);

        if (kojiHubURL == null) {
            throw new KojiClientException("Koji hub URL is not set");
        }

        LOGGER.info("Initializing Koji client session with URL {}", kojiHubURL);

        try (KojiClientSession session = new KojiClientSession(kojiHubURL)) {
            URL pncURL = config.getPncURL();
            BuildFinder buildFinder;

            if (pncURL == null) {
                LOGGER.warn("PNC support disabled because PNC URL is not set");
                buildFinder = new BuildFinder(session, config, analyzer, cacheManager);
            } else {
                LOGGER.info("Initializing PNC client with URL {}", pncURL);
                PncClient pncclient = new HashMapCachingPncClient(config);
                buildFinder = new BuildFinder(session, config, analyzer, cacheManager, pncclient);
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
        }

        return null;
    }

    public BuildConfig getBuildConfig() {
        return config;
    }
}
