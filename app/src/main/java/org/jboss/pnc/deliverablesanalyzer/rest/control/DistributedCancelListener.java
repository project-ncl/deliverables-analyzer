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
