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
package org.jboss.pnc.deliverablesanalyzer.app;

import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.ServerConfigurationBuilder;
import org.infinispan.commons.api.BasicCacheContainer;
import org.infinispan.commons.util.Version;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationChildBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.jboss.pnc.deliverablesanalyzer.core.BuildConfig;
import org.jboss.pnc.deliverablesanalyzer.core.ConfigDefaults;
import org.jboss.pnc.deliverablesanalyzer.model.cache.AnalyzerSchemaBuilderImpl;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksumType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class CacheProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheProvider.class);

    @Inject
    BuildConfig config;

    @ConfigProperty(name = "quarkus.infinispan-client.hosts")
    Optional<List<String>> infinispanRemoteServerList;

    @ConfigProperty(name = "quarkus.infinispan-client.username")
    Optional<String> infinispanUsername;

    @ConfigProperty(name = "quarkus.infinispan-client.password")
    Optional<String> infinispanPassword;

    @ConfigProperty(name = "infinispan.mode", defaultValue = "EMBEDDED")
    InfinispanMode infinispanMode;

    @Produces
    public BasicCacheContainer initCaches() throws IOException {
        return switch (infinispanMode) {
            case EMBEDDED -> setupEmbeddedCacheManager();
            case REMOTE -> setupDistributedCacheManager();
        };
    }

    public void close(@Disposes BasicCacheContainer cacheManager) {
        if (cacheManager instanceof Closeable) {
            try {
                ((Closeable) cacheManager).close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close cache manager", e);
            }
        }
    }

    private DefaultCacheManager setupEmbeddedCacheManager() throws IOException {
        LOGGER.info("Initializing Embedded Infinispan Cache ({})", Version.getVersion());
        ensureConfigurationDirectoryExists();

        Path cachePath = ConfigDefaults.CACHE_LOCATION;
        String cacheLocation = cachePath.toAbsolutePath().toString();

        LOGGER.info("Cache location is: {}", cacheLocation);

        if (!Files.exists(cachePath)) {
            Files.createDirectory(cachePath);
        }

        GlobalConfigurationChildBuilder globalConfig = new GlobalConfigurationBuilder();
        globalConfig.globalState()
                .persistentLocation(cacheLocation)
                .serialization()
                .addContextInitializer(new AnalyzerSchemaBuilderImpl())
                .allowList()
                .addRegexp(".*")
                .create();

        Configuration cacheConfig = new org.infinispan.configuration.cache.ConfigurationBuilder().expiration()
                .lifespan(config.cacheLifespan())
                .wakeUpInterval(-1L)
                .persistence()
                .passivation(false)
                .addSoftIndexFileStore()
                .segmented(true)
                .shared(false)
                .preload(true)
                .purgeOnStartup(false)
                .dataLocation(cacheLocation)
                .indexLocation(cacheLocation)
                .build();

        DefaultCacheManager cacheManager = new DefaultCacheManager(globalConfig.build());

        String checksumType = ChecksumType.SHA256.name();
        LOGGER.info("Setting up caches for {}", checksumType);

        cacheManager.defineConfiguration("file-" + checksumType, cacheConfig);

        return cacheManager;
    }

    private RemoteCacheManager setupDistributedCacheManager() {
        LOGGER.info("Initializing Remote Infinispan Cache ({})", Version.getVersion());

        if (infinispanRemoteServerList.isEmpty()) {
            throw new RuntimeException("infinispan server list missing");
        }
        if (infinispanUsername.isEmpty()) {
            throw new RuntimeException("infinispan username missing");
        }
        if (infinispanPassword.isEmpty()) {
            throw new RuntimeException("infinispan password missing");
        }

        ConfigurationBuilder builder = new ConfigurationBuilder();

        for (String server : infinispanRemoteServerList.get()) {
            String[] hostPort = server.split(":");
            ServerConfigurationBuilder serverBuilder = builder.addServer();
            serverBuilder.host(hostPort[0]);
            if (hostPort.length > 1) {
                serverBuilder.port(Integer.parseInt(hostPort[1]));
            }
        }

        builder.security().authentication().username(infinispanUsername.get()).password(infinispanPassword.get());

        builder.addContextInitializer(new AnalyzerSchemaBuilderImpl());

        return new RemoteCacheManager(builder.build());
    }

    private static void ensureConfigurationDirectoryExists() throws IOException {
        Path configPath = ConfigDefaults.CONFIG_PATH;

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

    enum InfinispanMode {
        REMOTE, EMBEDDED
    }
}
