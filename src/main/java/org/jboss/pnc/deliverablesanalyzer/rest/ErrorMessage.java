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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

public class ErrorMessage {
    @NotNull
    private final Exception exception;

    @Positive
    private final int code;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final String reason;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final String message;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<String> stackTrace = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final List<String> causeStackTrace = new ArrayList<>();

    public ErrorMessage(Exception exception) {
        this.exception = exception;
        Response.Status status = Response.Status.INTERNAL_SERVER_ERROR;

        if (exception instanceof WebApplicationException e) {
            try (Response response = e.getResponse()) {
                status = response.getStatusInfo().toEnum();
            }
        }

        if (exception.getCause() != null) {
            causeStackTrace.addAll(
                    Arrays.stream(exception.getCause().getStackTrace()).map(StackTraceElement::toString).toList());
        }

        message = exception.getMessage();
        reason = status.getReasonPhrase();
        code = status.getStatusCode();
        stackTrace.addAll(Arrays.stream(exception.getStackTrace()).map(StackTraceElement::toString).toList());
    }

    @JsonIgnore
    public Exception getException() {
        return exception;
    }

    public String getReason() {
        return reason;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getStackTrace() {
        return Collections.unmodifiableList(stackTrace);
    }

    public List<String> getCauseStackTrace() {
        return Collections.unmodifiableList(causeStackTrace);
    }
}
