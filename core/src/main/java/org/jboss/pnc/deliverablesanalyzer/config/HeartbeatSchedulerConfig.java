/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.config;

import org.jboss.pnc.common.http.PNCHttpClientConfig;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "delan.heartbeat-scheduler")
public interface HeartbeatSchedulerConfig extends PNCHttpClientConfig {
}
