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
package org.jboss.pnc.deliverablesanalyzer.rest.exception;

import java.util.UUID;

import org.jboss.pnc.api.dto.ExceptionResolution;
import org.jboss.pnc.api.enums.ResultStatus;

/**
 * Exception used when collecting reason and proposal for UI
 */
public class ReasonedException extends RuntimeException {

    private final String errorId;
    private final ResultStatus result;
    private final ExceptionResolution exceptionResolution;

    public ReasonedException(
            ResultStatus result,
            String reason,
            String proposal,
            Throwable cause) {
        super(reason, cause);
        this.errorId = UUID.randomUUID().toString();
        this.result = result;
        this.exceptionResolution = ExceptionResolution.builder()
                .reason(reason)
                .proposal(proposal)
                .build();
    }

    public ReasonedException(
            ResultStatus result,
            String reason,
            Throwable cause) {
        super(reason, cause);
        this.errorId = UUID.randomUUID().toString();
        this.result = result;
        this.exceptionResolution = ExceptionResolution.builder()
                .reason(reason)
                .proposal(this.getDefaultProposal())
                .build();
    }

    public String getErrorId() {
        return errorId;
    }

    public ResultStatus getResult() {
        return result;
    }

    public ExceptionResolution getExceptionResolution() {
        return exceptionResolution;
    }

    private String getDefaultProposal() {
        return String.format(
                "There is an internal system error, please contact PNC team "
                        + "at #forum-pnc-users (with the following ID: %s)",
                this.errorId);
    }
}
