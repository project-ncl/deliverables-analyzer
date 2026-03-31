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
package org.jboss.pnc.deliverablesanalyzer.pnc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.response.Page;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;

@ApplicationScoped
public class PncClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(PncClient.class);

    private static final String ONLY_BUILT = "build=isnull=false";

    @Inject
    @RestClient
    PncRestClient restClient;

    public Collection<Artifact> getArtifactsBySha256(String sha256) {
        try {
            Page<Artifact> page = restClient.getArtifacts(sha256, ONLY_BUILT);
            if (page == null || page.getContent() == null) {
                return Collections.emptyList();
            }
            return page.getContent();
        } catch (ClientWebApplicationException e) {
            LOGGER.error("Failed to fetch artifacts by sha256", e);
            throw e;
        }
    }
}
