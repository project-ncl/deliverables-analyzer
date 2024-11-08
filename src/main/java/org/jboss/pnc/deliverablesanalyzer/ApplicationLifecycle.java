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

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class ApplicationLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationLifecycle.class);

    public void onStart(@Observes StartupEvent event) {
        Ansi.setEnabled(false); // Turn of color output to logs
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("{} started", Version.getVersion());
        }
    }

    public void onStop(@Observes ShutdownEvent event) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("{} stopped", Version.getVersion());
        }
    }
}
