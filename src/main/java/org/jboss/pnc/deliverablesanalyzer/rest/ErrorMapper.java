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

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.ObjectMapper;

@Provider
public class ErrorMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception exception) {
        Response.Status status;

        if (exception instanceof WebApplicationException) {
            status = ((WebApplicationException) exception).getResponse().getStatusInfo().toEnum();
        } else {
            status = Response.Status.INTERNAL_SERVER_ERROR;
        }

        int code = status.getStatusCode();

        return Response.status(status)
                .entity(
                        new ObjectMapper().createObjectNode()
                                .put(
                                        "error",
                                        exception.getCause() != null ? exception.getCause().getMessage()
                                                : exception.getMessage())
                                .put("code", code))
                .build();
    }
}
