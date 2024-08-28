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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.ServerConfigurationBuilder;
import org.infinispan.commons.api.BasicCacheContainer;
import org.infinispan.commons.util.Version;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationChildBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.build.finder.core.ChecksumType;
import org.jboss.pnc.build.finder.core.ConfigDefaults;
import org.jboss.pnc.build.finder.protobuf.ProtobufSerializerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author jbrazdil
 */
public class CacheProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheProvider.class);

    @Inject
    BuildConfig config;

    /**
     * Specify list of Infinispan servers. Format: hostname[:port]. The ConfigProperty is the same as the one used by
     * infinispan-quarkus-client to help with future migration.
     */
    @ConfigProperty(name = "quarkus.infinispan-client.server-list")
    Optional<List<String>> infinispanRemoteServerList;

    /*
     * The ConfigProperty is the same as the one used by infinispan-quarkus-client to help with future migration.
     */
    @ConfigProperty(name = "quarkus.infinispan-client.auth-username")
    Optional<String> infinispanUsername;

    /*
     * The ConfigProperty is the same as the one used by infinispan-quarkus-client to help with future migration.
     */
    @ConfigProperty(name = "quarkus.infinispan-client.auth-password")
    Optional<String> infinispanPassword;

    /**
     * Set the infinispan mode as either embedded or remote.
     */
    @ConfigProperty(name = "infinispan.mode")
    InfinispanMode infinispanMode;

    private static void ensureConfigurationDirectoryExists() throws IOException {
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

    /**
     * Return the appropriate cache manager depending on the infinispan mode
     *
     * @return cache manager
     * @throws IOException something went wrong
     */
    @Produces
    public BasicCacheContainer initCaches() throws IOException {
        switch (infinispanMode) {
            case EMBEDDED:
                LOGGER.info("Using Embedded Infinispan cache");
                return setupEmbeddedCacheManager();
            case REMOTE:
                LOGGER.info("Using Remote Infinispan cache");
                return setupDistributedCacheManager();
            default:
                throw new RuntimeException("This infinispan mode has no cache manager");
        }
    }

    public void close(@Disposes BasicCacheContainer cacheManager) {
        if (cacheManager instanceof Closeable) {
            try {
                Closeable closeable = (Closeable) cacheManager;
                closeable.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close cache manager", e);
            }
        }
    }

    /**
     * Setup the embedded infinispan cache. The caches are also setup.
     *
     * @return embedded cache manager
     * @throws IOException if something went wrong
     */
    private DefaultCacheManager setupEmbeddedCacheManager() throws IOException {
        LOGGER.info("Initializing {} {} cache", Version.getBrandName(), Version.getVersion());
        ensureConfigurationDirectoryExists();

        Path cachePath = Paths.get(ConfigDefaults.CONFIG_PATH, "cache");
        String cacheLocation = cachePath.toAbsolutePath().toString();

        LOGGER.info("Cache location is: {}", cacheLocation);

        if (!Files.exists(cachePath)) {
            Files.createDirectory(cachePath);
        }

        if (!Files.isDirectory(cachePath)) {
            throw new IOException("Tried to set cache location to non-directory: " + cachePath);
        }

        if (!Files.isReadable(cachePath)) {
            throw new IOException("Cache location is not readable: " + cachePath);
        }

        if (!Files.isWritable(cachePath)) {
            throw new IOException("Cache location is not writable: " + cachePath);
        }

        GlobalConfigurationChildBuilder globalConfig = new GlobalConfigurationBuilder();

        globalConfig.globalState()
                .persistentLocation(cacheLocation)
                .serialization()
                .addContextInitializer(new ProtobufSerializerImpl())
                .allowList()
                .addRegexp(".*")
                .create();

        Configuration configuration = new org.infinispan.configuration.cache.ConfigurationBuilder().expiration()
                .lifespan(config.getCacheLifespan())
                .wakeUpInterval(-1L)
                .persistence()
                .passivation(false)
                .addSoftIndexFileStore()
                .segmented(true)
                .shared(false)
                .preload(true)
                .fetchPersistentState(true)
                .purgeOnStartup(false)
                .dataLocation(cacheLocation)
                .indexLocation(cacheLocation)
                .build();

        Set<ChecksumType> checksumTypes = config.getChecksumTypes();
        GlobalConfiguration globalConfiguration = globalConfig.build();
        DefaultCacheManager cacheManager = new DefaultCacheManager(globalConfiguration);

        LOGGER.info("Setting up caches for checksum types size: {}", checksumTypes.size());

        for (ChecksumType checksumType : checksumTypes) {
            cacheManager.defineConfiguration("files-" + checksumType, configuration);
            cacheManager.defineConfiguration("checksums-" + checksumType, configuration);
            cacheManager.defineConfiguration("checksums-pnc-" + checksumType, configuration);
            cacheManager.defineConfiguration("rpms-" + checksumType, configuration);
        }

        cacheManager.defineConfiguration("builds", configuration);
        cacheManager.defineConfiguration("builds-pnc", configuration);
        return cacheManager;
    }

    /**
     * Setup the distributed Infinispan cache manager from configs. It is assumed that the caches are already setup on
     * the remote Infinispan server.
     * <p>
     * In the future, this can be replaced by the built-in quarkus-infinispan-client once we don't use the embedded
     * infinispan feature.
     *
     * @return remote cache manager setup to talk to the distributed infinispan server
     * @throws RuntimeException if the infinispan remote server list, username, or password are not specified in the
     *         config
     */
    private RemoteCacheManager setupDistributedCacheManager() {
        throwRuntimeExceptionIfOptionalEmpty(infinispanRemoteServerList, "infinispan server list");
        throwRuntimeExceptionIfOptionalEmpty(infinispanUsername, "infinispan username");
        throwRuntimeExceptionIfOptionalEmpty(infinispanPassword, "infinispan password");

        ConfigurationBuilder builder = new ConfigurationBuilder();

        for (String server : infinispanRemoteServerList.get()) {
            String[] hostPort = server.split(":");

            ServerConfigurationBuilder serverBuilder = builder.addServer();
            serverBuilder.host(hostPort[0]);

            if (hostPort.length > 1) {
                serverBuilder.port(Integer.parseInt(hostPort[1]));
            }
        }

        // Tell Infinispan how to marshall and unmarshall the DTOs
        builder.addContextInitializer(new ProtobufSerializerImpl());
        builder.security().authentication().username(infinispanUsername.get()).password(infinispanPassword.get());
        return new RemoteCacheManager(builder.build());
    }

    private static void throwRuntimeExceptionIfOptionalEmpty(Optional<?> optional, String key) {
        if (optional.isEmpty()) {
            throw new RuntimeException(key + " is not specified in the config");
        }
    }

    /**
     * Enum to describe the possible Infinispan server mode.
     */
    enum InfinispanMode {
        REMOTE, EMBEDDED
    }
}
