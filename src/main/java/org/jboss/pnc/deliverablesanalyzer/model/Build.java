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

import java.util.HashSet;
import java.util.Set;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

public class Build {
    @NotBlank
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String identifier;

    @PositiveOrZero
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long kojiId;

    @PositiveOrZero
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long pncId;

    @NotNull
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean builtFromSource;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String source;

    @NotBlank
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BuildSystemType buildSystemType;

    @JsonIgnoreProperties("build")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<Artifact> artifacts = new HashSet<>();

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public Long getKojiId() {
        return kojiId;
    }

    public void setKojiId(Long kojiId) {
        this.kojiId = kojiId;
    }

    public Long getPncId() {
        return pncId;
    }

    public void setPncId(Long pncId) {
        this.pncId = pncId;
    }

    public Boolean getBuiltFromSource() {
        return builtFromSource;
    }

    public void setBuiltFromSource(Boolean builtFromSource) {
        this.builtFromSource = builtFromSource;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public BuildSystemType getBuildSystemType() {
        return buildSystemType;
    }

    public void setBuildSystemType(BuildSystemType buildSystemType) {
        this.buildSystemType = buildSystemType;
    }

    public Set<Artifact> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(Set<Artifact> artifacts) {
        this.artifacts = artifacts;
    }
}
