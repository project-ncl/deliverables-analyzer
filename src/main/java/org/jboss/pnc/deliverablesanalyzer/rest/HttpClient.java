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

import java.io.IOException;
import java.util.Collection;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.jboss.pnc.api.dto.Request;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Simple HTTP client wrapper
 *
 * @author Jakub Bartecek &lt;jbartece@redhat.com&gt;
 */
@ApplicationScoped
public class HttpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpClient.class);

    private final Client client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpClient() {
        client = ClientBuilder.newBuilder().build();
    }

    @PreDestroy
    void preDestroy() {
        client.close();
    }

    /**
     * Sends a request without an entity body The method validates if the remote endpoint responds with 200, otherwise
     * IOException is thrown
     *
     * @param request Request details
     * @throws Exception Thrown in case of the request failure
     */
    public void performHttpRequest(Request request) throws Exception {
        LOGGER.debug("Performing HTTP request with these parameters: {}", request);

        try (Response response = invokeHttpRequest(request, null)) {
            validateResponse(response);
        } catch (ProcessingException | IOException e) {
            LOGGER.debug("HTTP request failed!", e);
            throw e;
        }
    }

    /**
     * Sends a request with payload converted to JSON and application/json MIME type. The method validates if the remote
     * endpoint responds with 200, otherwise IOException is thrown
     *
     * @param request Request details
     * @param payload Serializable object to be converted to JSON
     * @throws IOException Thrown in case of the request failure
     */
    public void performHttpRequest(Request request, Object payload) throws Exception {
        LOGGER.debug("Performing HTTP request with these parameters: {} (payload: {})", request, payload);

        Response response = null;

        try {
            String json = objectMapper.writeValueAsString(payload);
            Entity<String> entity = Entity.json(json);
            response = invokeHttpRequest(request, entity);
            validateResponse(response);
        } catch (ProcessingException | IOException e) {
            LOGGER.debug("HTTP request failed!", e);
            throw e;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private Response invokeHttpRequest(Request request, Entity<?> entity) throws IOException, ProcessingException {
        WebTarget target = client.target(request.getUri().toString());
        Invocation.Builder requestBuilder = target.request().headers(headersToMap(request.getHeaders()));

        switch (request.getMethod()) {
            case GET:
                return requestBuilder.get();
            case POST:
                return requestBuilder.post(entity);
            case PUT:
                return requestBuilder.put(entity);
            case DELETE:
                return requestBuilder.delete();
            case HEAD:
                return requestBuilder.head();
            default:
                String failureMsg = String.format("Unsupported HTTP method provided: %s", request.getMethod());
                LOGGER.warn(failureMsg);
                throw new IOException(failureMsg);
        }
    }

    private void validateResponse(Response response) throws IOException {
        String responseEntity = response.readEntity(String.class);

        if (response.getStatus() != 200) {
            String failureMsg = String.format(
                    "Http request failed! ResponseCode: %s, Entity: %s",
                    response.getStatus(),
                    responseEntity != null ? responseEntity : "");

            LOGGER.warn(failureMsg);
            throw new IOException(failureMsg);

        } else {
            LOGGER.debug(
                    "Http request sent successfully. ResponseCode: {}, Entity: {}",
                    response.getStatus(),
                    responseEntity);
        }
    }

    private MultivaluedMap<String, Object> headersToMap(Collection<Request.Header> headers) {
        MultivaluedMap<String, Object> map = new MultivaluedMapImpl<>();
        headers.forEach(h -> map.add(h.getName(), h.getValue()));
        return map;
    }
}
