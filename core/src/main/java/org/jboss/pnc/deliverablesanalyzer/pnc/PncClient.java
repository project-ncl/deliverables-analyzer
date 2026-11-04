/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.pnc;

import java.util.Collection;
import java.util.Collections;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.response.Page;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            LOGGER.error("Failed to fetch PNC artifacts by sha256", e);
            throw e;
        }
    }
}
