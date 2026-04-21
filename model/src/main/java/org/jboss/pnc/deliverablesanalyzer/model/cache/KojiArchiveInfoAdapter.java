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
package org.jboss.pnc.deliverablesanalyzer.model.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import org.infinispan.protostream.annotations.ProtoAdapter;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.jboss.pnc.deliverablesanalyzer.model.finder.AnalyzerObjectMapper;

@ProtoAdapter(KojiArchiveInfo.class)
public class KojiArchiveInfoAdapter {
    private static final ObjectMapper MAPPER = new AnalyzerObjectMapper();

    @ProtoFactory
    KojiArchiveInfo create(String jsonData) {
        try {
            return MAPPER.readValue(jsonData, KojiArchiveInfo.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @ProtoField(1)
    String getJsonData(KojiArchiveInfo kojiArchiveInfo) {
        try {
            return MAPPER.writeValueAsString(kojiArchiveInfo);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
