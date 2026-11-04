/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.cache;

import org.infinispan.protostream.annotations.ProtoAdapter;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.jboss.pnc.deliverablesanalyzer.model.finder.AnalyzerObjectMapper;
import org.jboss.pnc.deliverablesanalyzer.model.finder.KojiBuild;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ProtoAdapter(KojiBuild.class)
public class KojiBuildAdapter {
    private static final ObjectMapper MAPPER = new AnalyzerObjectMapper();

    @ProtoFactory
    KojiBuild create(String jsonData) {
        try {
            return MAPPER.readValue(jsonData, KojiBuild.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @ProtoField(1)
    String getJsonData(KojiBuild kojiBuild) {
        try {
            return MAPPER.writeValueAsString(kojiBuild);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
