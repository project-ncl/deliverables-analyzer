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
package org.jboss.pnc.deliverablesanalyzer.model;

import java.io.Serial;
import java.io.Serializable;

import org.jboss.pnc.api.dto.Request;

/**
 * Response object of a started analysis
 */
public class AnalyzeResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Analysis ID
     */
    private String id;

    /**
     * Request definition to cancel this running analysis
     */
    private Request cancelRequest;

    public AnalyzeResponse(String id, Request cancelRequest) {
        this.id = id;
        this.cancelRequest = cancelRequest;
    }

    public AnalyzeResponse() {
    }

    public String getId() {
        return id;
    }

    public Request getCancelRequest() {
        return cancelRequest;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setCancelRequest(Request cancelRequest) {
        this.cancelRequest = cancelRequest;
    }

    @Override
    public String toString() {
        return "AnalyzeResponse{" + "id='" + id + '\'' + ", cancelRequest=" + cancelRequest + '}';
    }
}
