/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.rest.control;

import org.infinispan.client.hotrod.annotation.ClientCacheEntryCreated;
import org.infinispan.client.hotrod.annotation.ClientListener;
import org.infinispan.client.hotrod.event.ClientCacheEntryCreatedEvent;

@ClientListener
public class DistributedCancelListener {
    private final AnalyzeService analyzeService;

    public DistributedCancelListener(AnalyzeService analyzeService) {
        this.analyzeService = analyzeService;
    }

    @ClientCacheEntryCreated
    public void onCancelEvent(ClientCacheEntryCreatedEvent<String> event) {
        analyzeService.tryCancelLocalJob(event.getKey());
    }
}
