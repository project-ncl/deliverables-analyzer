/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.rest.control;

import java.time.ZonedDateTime;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.api.dto.ComponentVersion;
import org.jboss.pnc.deliverablesanalyzer.app.BuildInformationConstants;

@ApplicationScoped
public class VersionService {

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    public ComponentVersion getComponentVersion() {
        return ComponentVersion.builder()
                .name(name)
                .version(BuildInformationConstants.VERSION)
                .commit(BuildInformationConstants.COMMIT_HASH)
                .builtOn(ZonedDateTime.parse(BuildInformationConstants.BUILD_TIME))
                .build();
    }

    public String getVersion() {
        return getComponentVersion().toString();
    }
}
