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

import java.util.SortedSet;
import java.util.TreeSet;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Artifact {
    @NotBlank
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String identifier;

    @NotNull
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Type type;

    @Pattern(regexp = "^[a-f0-9]{32}$")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String md5;

    @Pattern(regexp = "^[a-f0-9]{40}$")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sha1;

    @Pattern(regexp = "^[a-f0-9]{64}$")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sha256;

    @Positive
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long kojiId;

    @Positive
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long pncId;

    @NotNull
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BuildSystemType buildSystemType;

    @NotNull
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean builtFromSource;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private SortedSet<String> filesNotBuiltFromSource = new TreeSet<>();

    @NotNull
    @Valid
    @JsonIgnore
    // @JsonIgnoreProperties("artifacts")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Build build;

    @JsonIgnoreProperties("artifact")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private MavenArtifact mavenArtifact;

    @JsonIgnoreProperties("artifact")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private NpmArtifact npmArtifact;

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public String getSha1() {
        return sha1;
    }

    public void setSha1(String sha1) {
        this.sha1 = sha1;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
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

    public BuildSystemType getBuildSystemType() {
        return buildSystemType;
    }

    public void setBuildSystemType(BuildSystemType buildSystemType) {
        this.buildSystemType = buildSystemType;
    }

    public Boolean getBuiltFromSource() {
        return builtFromSource;
    }

    public void setBuiltFromSource(Boolean builtFromSource) {
        this.builtFromSource = builtFromSource;
    }

    public SortedSet<String> getFilesNotBuiltFromSource() {
        return filesNotBuiltFromSource;
    }

    public void setFilesNotBuiltFromSource(SortedSet<String> filesNotBuiltFromSource) {
        this.filesNotBuiltFromSource = filesNotBuiltFromSource;
    }

    public Build getBuild() {
        return build;
    }

    public void setBuild(Build build) {
        this.build = build;
    }

    public MavenArtifact getMavenArtifact() {
        return mavenArtifact;
    }

    public void setMavenArtifact(MavenArtifact mavenArtifact) {
        this.mavenArtifact = mavenArtifact;
    }

    public NpmArtifact getNpmArtifact() {
        return npmArtifact;
    }

    public void setNpmArtifact(NpmArtifact npmArtifact) {
        this.npmArtifact = npmArtifact;
    }

    public enum Type {
        @JsonProperty("maven") MAVEN, @JsonProperty("npm") NPM
    }
}
