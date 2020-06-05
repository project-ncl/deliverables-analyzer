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
import java.util.List;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

public class ErrorInfo {
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

    public ErrorInfo(Exception exception) {
        this.exception = exception;
        Response.Status status;
        boolean isWAE = exception instanceof WebApplicationException;

        if (isWAE) {
            status = ((WebApplicationException) exception).getResponse().getStatusInfo().toEnum();
        } else {
            status = Response.Status.INTERNAL_SERVER_ERROR;
        }

        if (exception.getCause() != null) {
            this.causeStackTrace.addAll(
                    Arrays.asList(exception.getCause().getStackTrace())
                            .stream()
                            .map(StackTraceElement::toString)
                            .collect(Collectors.toList()));
        }

        this.message = exception.getMessage();
        this.reason = status.getReasonPhrase();
        this.code = status.getStatusCode();
        this.stackTrace.addAll(
                Arrays.asList(exception.getStackTrace())
                        .stream()
                        .map(StackTraceElement::toString)
                        .collect(Collectors.toList()));
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
        return stackTrace;
    }
}
