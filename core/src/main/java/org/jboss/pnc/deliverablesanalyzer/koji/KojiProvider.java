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
package org.jboss.pnc.deliverablesanalyzer.koji;

import com.redhat.red.build.koji.KojiClient;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.KojiClientHelper;
import com.redhat.red.build.koji.config.KojiConfig;
import com.redhat.red.build.koji.config.SimpleKojiConfigBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import org.commonjava.util.jhttpc.auth.MemoryPasswordManager;
import org.jboss.pnc.deliverablesanalyzer.config.BuildConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.concurrent.Executors;

@ApplicationScoped
public class KojiProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(KojiProvider.class);

    @Inject
    BuildConfig buildConfig;

    @Produces
    @ApplicationScoped
    public KojiClient createKojiClient() throws KojiClientException {
        URL kojiHubURL = buildConfig.kojiURL();

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
