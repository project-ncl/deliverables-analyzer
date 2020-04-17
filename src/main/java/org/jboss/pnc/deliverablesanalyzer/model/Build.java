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

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public class Build {
    @NotBlank
    private String identifier;

    @PositiveOrZero
    private Long kojiId;

    @PositiveOrZero
    private Long pncId;

    @NotNull
    private Boolean builtFromSource = Boolean.FALSE;

    private String source;

    @NotNull
    private BuildSystemType buildSystemType = BuildSystemType.UNKNOWN;

    // TODO
    // @Pattern(regexp = "^((?!anonymous).).*$")
    private String username;

    private Date created;

    @JsonIgnoreProperties("build")
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Set<Artifact> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(Set<Artifact> artifacts) {
        this.artifacts = artifacts;
    }
}
