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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.jboss.pnc.api.dto.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@ApplicationScoped
public class RequestExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestExecutor.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void executeRequest(Request request, Object entity) throws IOException {
        try (Client client = ClientBuilder.newClient()) {
            WebTarget target = client.target(request.getUri().toString());
            Invocation.Builder requestBuilder = target.request();
            if (request.getHeaders() != null) {
                request.getHeaders().forEach(header -> requestBuilder.header(header.getName(), header.getValue()));
            }

            Entity<String> entityPayload = null;
            if (entity != null) {
                entityPayload = Entity.json(objectMapper.writeValueAsString(entity));
            }

            try (Response response = requestBuilder.method(request.getMethod().toString(), entityPayload)) {
                String responseEntity = response.readEntity(String.class);

                if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
                    LOGGER.warn(
                            "Http request failed. ResponseCode: {}, Entity: {}",
                            response.getStatus(),
                            responseEntity != null ? responseEntity : "");
                    throw new IOException("Http request failed. ResponseCode: " + response.getStatus());
                } else if (response.getStatus() != Response.Status.OK.getStatusCode()
                        && response.getStatus() != Response.Status.NO_CONTENT.getStatusCode()) {
                    LOGGER.warn(
                            "Http request failed. ResponseCode: {}, Entity: {}",
                            response.getStatus(),
                            responseEntity != null ? responseEntity : "");
                    throw new IOException("Http request failed. ResponseCode: " + response.getStatus());
                } else {
                    LOGGER.debug(
                            "Http request sent successfully. ResponseCode: {}, Entity: {}",
                            response.getStatus(),
                            responseEntity);
                }
            } catch (ProcessingException e) {
                throw new IOException(e);
            }
        }
    }
}
