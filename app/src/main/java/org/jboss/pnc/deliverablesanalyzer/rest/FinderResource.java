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
package org.jboss.pnc.deliverablesanalyzer.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalysisReport;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

// TODO Tomas: Only for local tests, delete after

@Path("/build-finder")
public class FinderResource {

    @POST
    @Path("/callback")
    @Produces(MediaType.TEXT_PLAIN)
    public String callback(AnalysisReport report) throws IOException {
        if (report.isSuccess()) {

            UUID id = UUID.randomUUID();
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File("builds-" + id + ".json"), report.getResults());

            return "CALLBACK for "
                    + report.getResults().stream().map(FinderResult::getId).collect(Collectors.joining(", "));
        }
        return report.getResultStatus().name();
    }
}
