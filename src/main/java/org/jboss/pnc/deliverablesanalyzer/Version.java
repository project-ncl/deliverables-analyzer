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
import java.util.Properties;

import org.jboss.pnc.build.finder.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Version {
    private static final Logger LOGGER = LoggerFactory.getLogger(Version.class);

    public static final String APP_PROPERTIES = "app.properties";

    private static Properties properties;

    static {
        properties = new Properties();

        try (InputStream is = ApplicationLifecycle.class.getClassLoader().getResourceAsStream(APP_PROPERTIES)) {
            properties.load(is);
        } catch (IOException | NullPointerException e) {
            LOGGER.error("Failed to load file: {}", APP_PROPERTIES, e);
        }
    }

    private Version() {

    }

    public static String getVersion() {
        return "Deliverables Analyzer " + getVersionNumber() + " (SHA: " + getScmRevision() + ")/"
                + getKojiBuildFinderVersion() + " running on Quarkus " + getQuarkusVersion() + " built on "
                + getBuildDate() + " by " + getBuiltBy();
    }

    private static String getKojiBuildFinderVersion() {
        return "Build Finder " + Utils.getBuildFinderVersion() + " (SHA: " + Utils.getBuildFinderScmRevision() + ")";
    }

    private static String getAppProperty(String name) {
        return properties.getProperty(name, "unknown");
    }

    private static String getVersionNumber() {
        return getAppProperty("version");
    }

    private static String getScmRevision() {
        return getAppProperty("Scm-Revision");
    }

    private static String getBuildDate() {
        return getAppProperty("Build-Date");
    }

    private static String getBuiltBy() {
        return getAppProperty("Build-User");
    }

    private static String getQuarkusVersion() {
        return getAppProperty("Quarkus-Version");
    }
}
