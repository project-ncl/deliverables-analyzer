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
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import org.jboss.pnc.build.finder.core.BuildConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author jbrazdil
 */
@ApplicationScoped
public class ConfigProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigProvider.class);

    private static final String CONFIG_FILE = "custom_config.json";

    private final BuildConfig config;

    @Produces
    public BuildConfig getConfig() {
        return config;
    }

    public ConfigProvider() throws IOException {
        BuildConfig defaults = BuildConfig.load(ConfigProvider.class.getClassLoader());
        String customConfig = null;

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            Objects.requireNonNull(is, "Input stream was null when getting resource " + CONFIG_FILE);
            customConfig = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOGGER.debug("Found custom configuration: {}", customConfig);
        } catch (IOException e) {
            LOGGER.error("Could not read the custom configuration", e);
        }

        if (customConfig != null) {
            if (defaults == null) {
                LOGGER.debug("Default config is null, using custom config file");
                config = BuildConfig.load(customConfig);
            } else {
                LOGGER.debug("Default config is not null, merging it with custom config file");
                config = BuildConfig.merge(defaults, customConfig);
            }
        } else {
            LOGGER.debug("Custom config file not found, using default config!");
            config = defaults != null ? defaults : new BuildConfig();
        }

        LOGGER.debug("Configuration: {}", config);

        // set config values if defined in java system property (-D) or via env variable or application.properties
        setKojiHubURL(config);
        setKojiWebURL(config);
        setPncURL(config);

        // XXX: Force output directory since it defaults to "." which usually isn't the best
        Path tmpDir = Files.createTempDirectory("deliverables-analyzer-");

        config.setOutputDirectory(tmpDir.toAbsolutePath().toString());
    }

    /**
     * Override koji hub url in the config if 'koji.hub.url' defined in a system property, env variable, or in
     * application.properties.
     *
     * @param config config file to potentially override its kojiHubUrl
     *
     * @throws IOException if we can't parse the value as a URL
     */
    private void setKojiHubURL(BuildConfig config) throws IOException {
        Optional<String> optionalKojiHubURL = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("koji.hub.url", String.class);

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

    /**
     * Override koji web url in the config if 'koji.web.url' defined in a system property, env variable, or in
     * application.properties. Otherwise, use kojiHubUrl to generate the kojiWebUrl.
     *
     * @param config config file to potentially override its kojiWebUrl
     *
     * @throws IOException if we can't parse the value as a URL
     */
    private void setKojiWebURL(BuildConfig config) throws IOException {
        Optional<String> optionalKojiWebURL = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("koji.web.url", String.class);

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

    /**
     * Override pnc url in the config if 'pnc.url' defined in a system property, env variable, or in
     * application.properties.
     *
     * @param config config file to potentially override its pncUrl
     *
     * @throws IOException if we can't parse the value as a URL
     */
    private void setPncURL(BuildConfig config) throws IOException {
        Optional<String> optionalPncURL = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("pnc.url", String.class);

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
}
