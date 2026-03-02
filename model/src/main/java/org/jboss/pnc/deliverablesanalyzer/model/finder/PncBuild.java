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
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.BuildPushReport;
import org.jboss.pnc.dto.ProductVersion;
import org.jboss.pnc.dto.SCMRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PncBuild {

    private Build build;
    private BuildPushReport buildPushReport;
    private ProductVersion productVersion;

    private List<EnhancedArtifact> builtArtifacts = new ArrayList<>();

    public PncBuild(Build build) {
        this.build = build;
    }

    @JsonIgnore
    public boolean isImport() {
        return build == null || build.getScmRepository() == null || build.getScmRepository().getInternalUrl() == null;
    }

    @JsonIgnore
    public boolean isMaven() {
        return true;
    }

    @JsonIgnore
    public Optional<String> getSource() {
        return Optional.ofNullable(build).map(Build::getScmRepository).map(SCMRepository::getInternalUrl);
    }

    public Optional<BuildPushReport> getBuildPushReport() {
        return Optional.ofNullable(buildPushReport);
    }

}
