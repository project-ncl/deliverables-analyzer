/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "delan")
public interface ApplicationConfig {

    HeartbeatSchedulerConfig heartbeatScheduler();

    AnalyzerConfig analyzer();
}
