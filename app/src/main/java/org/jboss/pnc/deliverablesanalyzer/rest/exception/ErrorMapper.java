/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.rest.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ErrorMapper implements ExceptionMapper<Exception> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        LOGGER.error("Exception while processing request.", exception);
        ErrorMessage errorMessage = new ErrorMessage(exception);

        return Response.status(errorMessage.getCode()).type(MediaType.APPLICATION_JSON).entity(errorMessage).build();
    }
}
