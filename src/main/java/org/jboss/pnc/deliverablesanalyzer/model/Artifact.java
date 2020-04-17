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
import java.util.SortedSet;
import java.util.TreeSet;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public class Artifact {
    @NotBlank
    private String identifier;

    @NotNull
    private Type type = Type.NONE;

    @Pattern(regexp = "^[a-f0-9]{32}$")
    private String md5;

    @Pattern(regexp = "^[a-f0-9]{40}$")
    private String sha1;

    @Pattern(regexp = "^[a-f0-9]{64}$")
    private String sha256;

    @Positive
    private Long kojiId;

    @Positive
    private Long pncId;

    @NotNull
    private BuildSystemType buildSystemType = BuildSystemType.UNKNOWN;

    @NotNull
    private Boolean builtFromSource = Boolean.FALSE;

    private SortedSet<String> filesNotBuiltFromSource = new TreeSet<>();

    @NotNull
    @Valid
    @JsonIgnoreProperties("artifacts")
    private Build build;

    @JsonIgnoreProperties("artifact")
    private MavenArtifact mavenArtifact;

    @JsonIgnoreProperties("artifact")
    private NpmArtifact npmArtifact;

    // TODO
    // @Pattern(regexp = "^((?!anonymous).).*$")
    private String username;

    private Date created;

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

    public enum Type {
        NONE, MAVEN, NPM
    }
}
