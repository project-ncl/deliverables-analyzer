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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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

    /**
     * Number of artifacts requested per page when paginating a batched query.
     */
    private static final int PAGE_SIZE = 200;

    @Inject
    @RestClient
    PncRestClient restClient;

    /**
     * Looks up all PNC artifacts matching any of the given sha256 checksums using a single RSQL query (plus follow-up
     * requests if the result spans multiple pages), rather than one HTTP request per checksum.
     *
     * @param sha256s the sha256 checksums to look up
     * @return every matching (built) artifact across all the given checksums; callers can group by
     *         {@link Artifact#getSha256()}
     */
    public Collection<Artifact> getArtifactsBySha256(Collection<String> sha256s) {
        if (sha256s == null || sha256s.isEmpty()) {
            return Collections.emptyList();
        }

        String q = "sha256=in=(" + String.join(",", sha256s) + ");" + ONLY_BUILT;

        try {
            List<Artifact> artifacts = new ArrayList<>();
            int pageIndex = 0;
            int totalPages;
            do {
                Page<Artifact> page = restClient.getArtifacts(q, pageIndex, PAGE_SIZE);
                if (page == null || page.getContent() == null) {
                    break;
                }
                artifacts.addAll(page.getContent());
                totalPages = page.getTotalPages();
                pageIndex++;
            } while (pageIndex < totalPages);
            return artifacts;
        } catch (ClientWebApplicationException e) {
            LOGGER.error("Failed to fetch PNC artifacts by sha256", e);
            throw e;
        }
    }
}
