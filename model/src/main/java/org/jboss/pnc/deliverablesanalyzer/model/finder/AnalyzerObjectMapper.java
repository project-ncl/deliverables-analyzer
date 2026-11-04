/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import java.io.Serial;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class AnalyzerObjectMapper extends ObjectMapper {
    @Serial
    private static final long serialVersionUID = -6336680901444277747L;

    public AnalyzerObjectMapper() {
        enable(SerializationFeature.INDENT_OUTPUT);
        enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        registerModule(new Jdk8Module());
        registerModule(new JavaTimeModule());
    }
}
