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
package org.jboss.pnc.deliverablesanalyzer.app.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.oidc.client.OidcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.jboss.pnc.common.concurrent.HeartbeatScheduler;
import org.jboss.pnc.common.concurrent.mdc.MDCScheduledThreadPoolExecutor;
import org.jboss.pnc.common.http.PNCHttpClient;
import org.jboss.pnc.deliverablesanalyzer.app.DeliverablesAnalyzerConfig;

import java.util.concurrent.ScheduledExecutorService;

@ApplicationScoped
public class BeanFactory {

    @Produces
    @ApplicationScoped
    public PNCHttpClient pncHttpClient(
            ObjectMapper objectMapper,
            DeliverablesAnalyzerConfig config,
            OidcClient oidcClient) {
        PNCHttpClient client = new PNCHttpClient(objectMapper, config.pncHttpClientConfig());
        client.setTokenSupplier(() -> oidcClient.getTokens().await().indefinitely().getAccessToken());
        return client;
    }

    @Produces
    @ApplicationScoped
    public HeartbeatScheduler heartbeatScheduler(
            ScheduledExecutorService scheduledExecutor,
            PNCHttpClient pncHttpClient) {
        MDCScheduledThreadPoolExecutor mdcExecutor = new MDCScheduledThreadPoolExecutor(scheduledExecutor);
        return new HeartbeatScheduler(mdcExecutor, pncHttpClient);
    }
}
