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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.ws.rs.BadRequestException;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.io.FileUtils;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.jboss.marshalling.commons.GenericJBossMarshaller;
import org.infinispan.manager.DefaultCacheManager;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.build.finder.core.BuildFinder;
import org.jboss.pnc.build.finder.core.BuildSystem;
import org.jboss.pnc.build.finder.core.BuildSystemInteger;
import org.jboss.pnc.build.finder.core.Checksum;
import org.jboss.pnc.build.finder.core.ChecksumType;
import org.jboss.pnc.build.finder.core.ConfigDefaults;
import org.jboss.pnc.build.finder.core.DistributionAnalyzer;
import org.jboss.pnc.build.finder.core.Utils;
import org.jboss.pnc.build.finder.koji.KojiBuild;
import org.jboss.pnc.build.finder.koji.KojiClientSession;
import org.jboss.pnc.build.finder.koji.KojiLocalArchive;
import org.jboss.pnc.build.finder.pnc.client.HashMapCachingPncClient;
import org.jboss.pnc.build.finder.pnc.client.PncClient;
import org.jboss.pnc.deliverablesanalyzer.model.Artifact;
import org.jboss.pnc.deliverablesanalyzer.model.Build;
import org.jboss.pnc.deliverablesanalyzer.model.BuildSystemType;
import org.jboss.pnc.deliverablesanalyzer.model.MavenArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.NpmArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;

public class Finder {
    private static final Logger LOGGER = LoggerFactory.getLogger(Finder.class);

    private final File configFile = new File(ConfigDefaults.CONFIG);

    private DefaultCacheManager cacheManager;

    private BuildConfig config;

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

        config.setOutputDirectory(tmpDir.toString());

        LOGGER.info("Output directory set to: {}", config.getOutputDirectory());

        return config;
    }

    @SuppressWarnings("deprecation")
    private void initCaches(BuildConfig config) throws IOException {
        KojiBuild.KojiBuildExternalizer externalizer = new KojiBuild.KojiBuildExternalizer();
        GlobalConfigurationBuilder globalConfigurationBuilder = new GlobalConfigurationBuilder();
        globalConfigurationBuilder.serialization()
                .marshaller(new GenericJBossMarshaller())
                .addAdvancedExternalizer(externalizer.getId(), externalizer)
                .whiteList()
                .addRegexp(".*")
                .create();
        GlobalConfiguration globalConfiguration = globalConfigurationBuilder.build();
        cacheManager = new DefaultCacheManager(globalConfiguration);

        ensureConfigurationDirectoryExists();

        Path locationPath = Paths.get(ConfigDefaults.CONFIG_PATH, "cache");

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

        String location = locationPath.toAbsolutePath().toString();
        Configuration configuration = new ConfigurationBuilder().expiration()
                .lifespan(config.getCacheLifespan())
                .maxIdle(config.getCacheMaxIdle())
                .wakeUpInterval(-1L)
                .persistence()
                .passivation(false)
                .addSingleFileStore()
                .shared(false)
                .preload(true)
                .fetchPersistentState(true)
                .purgeOnStartup(false)
                .location(location)
                .build();
        Set<ChecksumType> checksumTypes = config.getChecksumTypes();

        for (ChecksumType checksumType : checksumTypes) {
            cacheManager.defineConfiguration("files-" + checksumType, configuration);
            cacheManager.defineConfiguration("checksums-" + checksumType, configuration);
            cacheManager.defineConfiguration("checksums-pnc-" + checksumType, configuration);
            cacheManager.defineConfiguration("rpms-" + checksumType, configuration);
        }

        cacheManager.defineConfiguration("builds", configuration);
        cacheManager.defineConfiguration("builds-pnc", configuration);
    }

    // TODO: handle exceptions
    public List<Build> find(URL url, String username) {
        LOGGER.info("Find: {} for {}", url, username);

        Map<BuildSystemInteger, KojiBuild> builds = Collections.emptyMap();
        ExecutorService pool = null;

        try {
            config = setupBuildConfig();
            Path filename = Paths.get(url.getPath()).getFileName();
            File file = Paths.get(config.getOutputDirectory()).resolve(filename).toFile();

            LOGGER.info("Copying {} to {}", url, file);

            FileUtils.copyURLToFile(url, file);

            List<File> inputs = Collections.singletonList(file);

            if (cacheManager == null && !config.getDisableCache()) {
                initCaches(config);
            }

            pool = Executors.newFixedThreadPool(1 + config.getChecksumTypes().size());
            Map<ChecksumType, MultiValuedMap<String, String>> checksums;
            DistributionAnalyzer analyzer = new DistributionAnalyzer(inputs, config, cacheManager);
            Future<Map<ChecksumType, MultiValuedMap<String, String>>> futureChecksum = pool.submit(analyzer);

            try (KojiClientSession session = new KojiClientSession(config.getKojiHubURL())) {
                BuildFinder finder;

                if (config.getPncURL() != null) {
                    PncClient pncclient = new HashMapCachingPncClient(config);
                    LOGGER.info("Initialized PNC client with URL {}", config.getPncURL());
                    finder = new BuildFinder(session, config, analyzer, cacheManager, pncclient);
                } else {
                    finder = new BuildFinder(session, config, analyzer, cacheManager);
                }

                Future<Map<BuildSystemInteger, KojiBuild>> futureBuilds = pool.submit(finder);

                try {
                    checksums = futureChecksum.get();
                    builds = futureBuilds.get();

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Got {} checksums and {} builds", checksums.size(), checksums.size());
                    }
                } catch (ExecutionException e) {
                    // throw new KojiClientException("", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (KojiClientException e) {
                // ignore
            }
        } catch (IOException e) {
            // throw new KojiClientException("Error getting url " + url + " to file " + file, e);
        } finally {
            Path outputDirectory = Paths.get(config.getOutputDirectory());

            try {
                LOGGER.info("Cleanup after finding {}", url);

                Files.walk(outputDirectory).sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        LOGGER.info("Delete: {}", path);

                        Files.delete(path);
                    } catch (IOException e) {
                        // ignore
                    }
                });
            } catch (IOException e) {
                // ignore
            }

            if (cacheManager != null) {
                try {
                    cacheManager.close();
                } catch (IOException e) {
                    // ignore
                }
            }

            if (pool != null) {
                Utils.shutdownAndAwaitTermination(pool);
            }
        }

        return toBuildList(Collections.unmodifiableMap(builds), username);
    }

    private List<Build> toBuildList(Map<BuildSystemInteger, KojiBuild> builds, String username) {
        int numBuilds = builds.size();
        List<Build> buildList = new ArrayList<>(numBuilds);
        int buildCount = 0;

        Set<Map.Entry<BuildSystemInteger, KojiBuild>> entrySet = builds.entrySet();

        for (Map.Entry<BuildSystemInteger, KojiBuild> entry : entrySet) {
            buildCount++;

            BuildSystemInteger buildSystemInteger = entry.getKey();

            if (buildSystemInteger.getValue().equals(Integer.valueOf(0))) {
                continue;
            }

            BuildSystemType buildSystemType;

            if (buildSystemInteger.getBuildSystem().equals(BuildSystem.pnc)) {
                buildSystemType = BuildSystemType.PNC;
            } else {
                buildSystemType = BuildSystemType.KOJI;
            }

            KojiBuild kojiBuild = entry.getValue();
            String identifier = kojiBuild.getBuildInfo().getNvr();

            LOGGER.info("Build: {} / {} ({}.{})", buildCount, numBuilds, identifier, buildSystemType);

            // TODO
            Build existingBuild = null;
            Build build;

            if (existingBuild == null) {
                build = new Build();

                build.setIdentifier(identifier);
                build.setBuildSystemType(buildSystemType);

                if (build.getBuildSystemType().equals(BuildSystemType.PNC)) {
                    build.setPncId(Long.valueOf(kojiBuild.getBuildInfo().getId()));
                } else {
                    build.setKojiId(Long.valueOf(kojiBuild.getBuildInfo().getId()));
                }

                build.setSource(kojiBuild.getSource());
                build.setBuiltFromSource(!kojiBuild.isImport());

                build.setUsername(username);
                build.setCreated(new Date());
            } else {
                build = existingBuild;
            }

            List<KojiLocalArchive> localArchives = kojiBuild.getArchives();
            int numArchives = localArchives.size();
            int archiveCount = 0;

            for (KojiLocalArchive localArchive : localArchives) {
                archiveCount++;

                KojiArchiveInfo archiveInfo = localArchive.getArchive();
                String artifactIdentifier;
                Artifact artifact;
                MavenArtifact mavenArtifact = null;
                NpmArtifact npmArtifact = null;

                if (archiveInfo.getBuildType().equals("maven")) {
                    String groupId = archiveInfo.getGroupId();
                    String artifactId = archiveInfo.getArtifactId();
                    String type = archiveInfo.getExtension() != null ? archiveInfo.getExtension() : "";
                    String version = archiveInfo.getVersion();
                    String classifier = archiveInfo.getClassifier() != null ? archiveInfo.getClassifier() : "";
                    artifactIdentifier = String.join(":", groupId, artifactId, type, version, classifier);
                    mavenArtifact = new MavenArtifact();
                    mavenArtifact.setGroupId(groupId);
                    mavenArtifact.setArtifactId(artifactId);
                    mavenArtifact.setType(type);
                    mavenArtifact.setVersion(version);
                    mavenArtifact.setClassifier(classifier);
                } else if (archiveInfo.getBuildType().equals("npm")) { // TODO: NPM support doesn't exist yet
                    String name = archiveInfo.getArtifactId();
                    String version = archiveInfo.getVersion();
                    artifactIdentifier = String.join(":", name, version);
                    npmArtifact = new NpmArtifact();
                    npmArtifact.setName(name);
                    npmArtifact.setVersion(version);
                } else {
                    throw new BadRequestException(
                            "Archive " + archiveInfo.getArtifactId() + " had unhandled artifact type: "
                                    + archiveInfo.getBuildType());
                }

                LOGGER.info("Artifact: {} / {} ({})", archiveCount, numArchives, artifactIdentifier);

                // TODO
                Artifact existingArtifact = null;

                if (existingArtifact == null) {
                    artifact = new Artifact();

                    artifact.setIdentifier(artifactIdentifier);
                    artifact.setBuildSystemType(buildSystemType);
                    artifact.setBuiltFromSource(localArchive.isBuiltFromSource());
                    artifact.getFilesNotBuiltFromSource().addAll(localArchive.getUnmatchedFilenames());

                    Collection<Checksum> checksums = localArchive.getChecksums();

                    for (Checksum checksum : checksums) {
                        switch (checksum.getType()) {
                            case md5:
                                artifact.setMd5(checksum.getValue());
                                break;
                            case sha1:
                                artifact.setSha1(checksum.getValue());
                                break;
                            case sha256:
                                artifact.setSha256(checksum.getValue());
                                break;
                            default:
                                break;
                        }
                    }

                    if (kojiBuild.isPnc()) {
                        artifact.setPncId(Long.valueOf(archiveInfo.getArchiveId()));
                    } else {
                        artifact.setKojiId(Long.valueOf(archiveInfo.getArchiveId()));
                    }

                    artifact.setBuild(build);

                    artifact.setUsername(username);
                    artifact.setCreated(new Date());

                    build.getArtifacts().add(artifact);

                    if (mavenArtifact != null) {
                        mavenArtifact.setArtifact(artifact);
                        mavenArtifact.setUsername(username);
                        mavenArtifact.setCreated(new Date());
                        artifact.setMavenArtifact(mavenArtifact);
                        artifact.setType(Artifact.Type.MAVEN);
                    } else if (npmArtifact != null) {
                        npmArtifact.setArtifact(artifact);
                        npmArtifact.setUsername(username);
                        npmArtifact.setCreated(new Date());
                        artifact.setNpmArtifact(npmArtifact);
                        artifact.setType(Artifact.Type.NPM);
                    }
                } else {
                    build.getArtifacts().add(existingArtifact);
                }
            }

            buildList.add(build);
        }

        LOGGER.info("Builds map has size {} and builds list has size {}", builds.size(), buildList.size());

        return Collections.unmodifiableList(buildList);
    }

    public BuildConfig getBuildConfig() {
        return config;
    }
}
