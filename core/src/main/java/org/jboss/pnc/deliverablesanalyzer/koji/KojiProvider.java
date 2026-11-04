/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.koji;

import java.net.URL;
import java.util.concurrent.Executors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;

import org.commonjava.util.jhttpc.auth.MemoryPasswordManager;
import org.jboss.pnc.deliverablesanalyzer.config.AnalyzerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClient;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.KojiClientHelper;
import com.redhat.red.build.koji.config.KojiConfig;
import com.redhat.red.build.koji.config.SimpleKojiConfigBuilder;

@ApplicationScoped
public class KojiProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(KojiProvider.class);

    @Inject
    AnalyzerConfig analyzerConfig;

    @Produces
    @ApplicationScoped
    public KojiClient createKojiClient() throws KojiClientException {
        URL kojiHubURL = analyzerConfig.kojiUrl();

        if (kojiHubURL == null) {
            LOGGER.warn("Koji hub URL is not set. Koji client will not be initialized.");
            return null;
        }

        LOGGER.info("Initializing Koji client with URL {}", kojiHubURL);

        KojiConfig kojiConfig = new SimpleKojiConfigBuilder().withKojiURL(kojiHubURL.toExternalForm())
                .withMaxConnections(13)
                .withTimeout(900)
                .withConnectionPoolTimeout(900)
                .build();

        return new KojiClient(kojiConfig, new MemoryPasswordManager(), Executors.newFixedThreadPool(1));
    }

    @Produces
    @ApplicationScoped
    public KojiClientHelper createKojiHelper(KojiClient client) {
        if (client == null) {
            return null;
        }
        return new KojiClientHelper(client);
    }

    public void closeKojiClient(@Disposes KojiClient client) {
        if (client != null) {
            LOGGER.info("Closing Koji client...");
            client.close();
        }
    }
}
