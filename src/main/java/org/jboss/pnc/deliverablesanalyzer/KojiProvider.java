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

import java.net.URL;

import org.jboss.pnc.build.finder.core.BuildConfig;
import org.jboss.pnc.build.finder.koji.KojiClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 *
 * @author jbrazdil
 */
public class KojiProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(KojiProvider.class);

    @Inject
    BuildConfig config;

    @Produces
    @DefaultBean
    public KojiClientSession createSession() throws KojiClientException {
        LOGGER.info("Using default Koji ClientSession");

        URL kojiHubURL = config.getKojiHubURL();

        if (kojiHubURL == null) {
            throw new KojiClientException("Koji hub URL is not set");
        }

        LOGGER.info("Initializing Koji client session with URL {}", kojiHubURL);
        return new KojiClientSession(kojiHubURL);
    }

    public void close(@Disposes KojiClientSession session) {
        session.close();
    }

}
