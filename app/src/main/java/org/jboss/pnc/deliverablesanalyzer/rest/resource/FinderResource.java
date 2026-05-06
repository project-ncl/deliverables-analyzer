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
package org.jboss.pnc.deliverablesanalyzer.rest.resource;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.profile.IfBuildProfile;

@Path("/build-finder")
@IfBuildProfile("dev")
public class FinderResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinderResource.class);

    @Inject
    ObjectMapper mapper;

    @POST
    @Path("/callback")
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public String callback(AnalysisReport report) throws IOException {
        if (report == null) {
            LOGGER.warn("Received a null report in dev callback endpoint.");
            return "NULL_REPORT";
        }

        UUID id = UUID.randomUUID();
        File outputFile = new File("report-" + id + ".json");

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, report);
            LOGGER.info("Saved dev test report to: {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write report to file: {}", outputFile.getAbsolutePath(), e);
            return "FILE_WRITE_ERROR";
        }

        if (report.getResultStatus() == null) {
            LOGGER.warn("Report received, but ResultStatus was null.");
            return "STATUS_MISSING";
        }

        return report.getResultStatus().name();
    }
}
