/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.pnc.deliverablesanalyzer.rest.control.VersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class ApplicationLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationLifecycle.class);

    @Inject
    VersionService versionService;

    public void onStart(@Observes StartupEvent event) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("{} started", versionService.getVersion());
        }
    }

    public void onStop(@Observes ShutdownEvent event) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("{} stopped", versionService.getVersion());
        }
    }
}
