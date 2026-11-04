/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.app;

/**
 * The values in here are auto-generated from Maven plugin maven-replacer-plugin and the template is found in the
 * template folder
 */
public final class BuildInformationConstants {

    public static final String VERSION = "@version@";
    public static final String COMMIT_HASH = "@commit-id@";
    public static final String BUILD_TIME = "@build-time@";

    private BuildInformationConstants() {
        // Prevent instantiation
    }
}
