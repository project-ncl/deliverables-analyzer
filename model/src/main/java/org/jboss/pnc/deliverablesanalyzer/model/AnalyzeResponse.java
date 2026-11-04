/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model;

import java.io.Serial;
import java.io.Serializable;

import org.jboss.pnc.api.dto.Request;

public record AnalyzeResponse(String id, Request cancelRequest) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
