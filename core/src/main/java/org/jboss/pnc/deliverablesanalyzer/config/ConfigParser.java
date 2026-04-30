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
package org.jboss.pnc.deliverablesanalyzer.config;

import java.io.IOException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.model.finder.AnalyzerObjectMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

@ApplicationScoped
public class ConfigParser {

    private static final ObjectMapper OBJECT_MAPPER = new AnalyzerObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Inject
    BuildConfig buildConfig;

    public BuildSpecificConfig parseConfig(String configJson) {
        BuildSpecificConfig buildSpecificConfig = new BuildSpecificConfig(
                buildConfig.archiveExtensions(),
                buildConfig.excludes());
        if (configJson == null) {
            return buildSpecificConfig;
        }

        try {
            BuildSpecificConfig parseConfig = OBJECT_MAPPER.readValue(configJson, BuildSpecificConfig.class);
            if (parseConfig.getExcludes() != null) {
                buildSpecificConfig.setExcludes(parseConfig.getExcludes());
            }
            if (parseConfig.getArchiveExtensions() != null) {
                buildSpecificConfig.setArchiveExtensions(parseConfig.getArchiveExtensions());
            }
            return buildSpecificConfig;
        } catch (IOException e) {
            throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "Error parsing the provided config", e);
        }
    }
}
