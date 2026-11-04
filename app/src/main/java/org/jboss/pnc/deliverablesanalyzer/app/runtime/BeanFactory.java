/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.app.runtime;

import java.util.concurrent.ScheduledExecutorService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.jboss.pnc.common.concurrent.HeartbeatScheduler;
import org.jboss.pnc.common.concurrent.mdc.MDCScheduledThreadPoolExecutor;
import org.jboss.pnc.common.http.PNCHttpClient;
import org.jboss.pnc.deliverablesanalyzer.config.HeartbeatSchedulerConfig;
import org.jboss.pnc.quarkus.client.auth.runtime.PNCClientAuth;

import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class BeanFactory {

    @Produces
    @ApplicationScoped
    public PNCHttpClient pncHttpClient(
            ObjectMapper objectMapper,
            HeartbeatSchedulerConfig config,
            PNCClientAuth pncClientAuth) {
        PNCHttpClient client = new PNCHttpClient(objectMapper, config);
        client.setAuthValueSupplier(pncClientAuth::getHttpAuthorizationHeaderValue);
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
