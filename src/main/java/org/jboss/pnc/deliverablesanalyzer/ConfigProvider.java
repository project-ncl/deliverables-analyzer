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
import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.build.finder.core.ConfigDefaults;

/**
 *
 * @author jbrazdil
 */
@ApplicationScoped
public class ConfigProvider {

    private static final String DEFAULT_CONFIG_LOCATION = "./" + ConfigDefaults.CONFIG_FILE;
    private final File configFile = new File(DEFAULT_CONFIG_LOCATION);
    private BuildConfig config;

    @Produces
    public BuildConfig getConfig() {
        return config;
    }

    public ConfigProvider() throws IOException {
        var defaults = BuildConfig.load(ConfigProvider.class.getClassLoader());

        if (configFile.exists()) {
            if (defaults == null) {
                config = BuildConfig.load(configFile);
            } else {
                config = BuildConfig.merge(defaults, configFile);
            }
        } else {
            config = Objects.requireNonNullElseGet(defaults, BuildConfig::new);
        }

        setKojiHubURL(config);
        setKojiWebURL(config);
        setPncURL(config);

        // XXX: Force output directory since it defaults to "." which usually isn't the best
        var tmpDir = Files.createTempDirectory("deliverables-analyzer-");

        config.setOutputDirectory(tmpDir.toAbsolutePath().toString());
    }

    private void setKojiHubURL(BuildConfig config) throws IOException {
        var optionalKojiHubURL = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("koji.hub.url", String.class);

        if (optionalKojiHubURL.isPresent()) {
            var s = optionalKojiHubURL.get();

            try {
                var kojiHubURL = new URL(s);
                config.setKojiHubURL(kojiHubURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad Koji hub URL: " + s, e);
            }
        }
    }

    private void setKojiWebURL(BuildConfig config) throws IOException {
        var optionalKojiWebURL = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("koji.web.url", String.class);

        if (optionalKojiWebURL.isPresent()) {
            var s = optionalKojiWebURL.get();

            try {
                var kojiWebURL = new URL(s);
                config.setKojiWebURL(kojiWebURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad Koji web URL: " + s, e);
            }
        } else if (config.getKojiWebURL() == null && config.getKojiHubURL() != null) {
            // XXX: hack for missing koji.web.url
            var s = config.getKojiHubURL().toExternalForm().replace("hub.", "web.").replace("hub", "");

            try {
                var kojiWebURL = new URL(s);
                config.setKojiWebURL(kojiWebURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad Koji web URL: " + s, e);
            }
        }
    }

    private void setPncURL(BuildConfig config) throws IOException {
        var optionalPncURL = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("pnc.url", String.class);

        if (optionalPncURL.isPresent()) {
            var s = optionalPncURL.get();

            try {
                var pncURL = new URL(s);
                config.setPncURL(pncURL);
            } catch (MalformedURLException e) {
                throw new IOException("Bad PNC URL: " + s, e);
            }
        }

    }
}
