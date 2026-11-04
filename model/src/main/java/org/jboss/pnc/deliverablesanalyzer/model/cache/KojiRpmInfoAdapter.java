/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.cache;

import org.infinispan.protostream.annotations.ProtoAdapter;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.jboss.pnc.deliverablesanalyzer.model.finder.AnalyzerObjectMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.red.build.koji.model.xmlrpc.KojiRpmInfo;

@ProtoAdapter(KojiRpmInfo.class)
public class KojiRpmInfoAdapter {
    private static final ObjectMapper MAPPER = new AnalyzerObjectMapper();

    @ProtoFactory
    KojiRpmInfo create(String jsonData) {
        try {
            return MAPPER.readValue(jsonData, KojiRpmInfo.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @ProtoField(1)
    String getJsonData(KojiRpmInfo kojiRpmInfo) {
        try {
            return MAPPER.writeValueAsString(kojiRpmInfo);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
