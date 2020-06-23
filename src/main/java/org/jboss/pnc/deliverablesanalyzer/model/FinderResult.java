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

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.BadRequestException;

import org.jboss.pnc.build.finder.core.BuildStatistics;
import org.jboss.pnc.build.finder.core.BuildSystem;
import org.jboss.pnc.build.finder.core.BuildSystemInteger;
import org.jboss.pnc.build.finder.core.Checksum;
import org.jboss.pnc.build.finder.koji.KojiBuild;
import org.jboss.pnc.build.finder.koji.KojiLocalArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;

public class FinderResult {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinderResult.class);

    @NotEmpty
    @Pattern(regexp = "^[a-f0-9]{8}$")
    private String id;

    private URL url;

    @NotNull
    @Valid
    private final Set<Build> builds;

    @NotNull
    @Valid
    private final Set<Artifact> notFoundArtifacts;

    @NotNull
    @Valid
    private final BuildStatistics statistics;

    public FinderResult() {
        this.builds = Collections.emptySet();
        this.notFoundArtifacts = Collections.emptySet();
        this.statistics = new BuildStatistics(Collections.emptyList());
    }

    public FinderResult(String id, URL url, Map<BuildSystemInteger, KojiBuild> builds) {
        this.id = id;
        this.url = url;
        this.builds = getFoundBuilds(builds);
        this.notFoundArtifacts = getNotFoundArtifacts(builds);
        this.statistics = new BuildStatistics(getBuildsAsList(builds));
    }

    public String getId() {
        return id;
    }

    public URL getUrl() {
        return url;
    }

    public Set<Build> getBuilds() {
        return Collections.unmodifiableSet(builds);
    }

    public Set<Artifact> getNotFoundArtifacts() {
        return Collections.unmodifiableSet(notFoundArtifacts);
    }

    public BuildStatistics getStatistics() {
        return statistics;
    }

    private void setArtifactChecksums(Artifact artifact, Collection<Checksum> checksums) {
        for (Checksum checksum : checksums) {
            switch (checksum.getType()) {
                case md5:
                    artifact.setMd5(checksum.getValue());
                    break;
                case sha1:
                    artifact.setSha1(checksum.getValue());
                    break;
                case sha256:
                    artifact.setSha256(checksum.getValue());
                    break;
                default:
                    break;
            }
        }
    }

    private MavenArtifact createMavenArtifact(KojiArchiveInfo archiveInfo) {
        String groupId = archiveInfo.getGroupId();
        String artifactId = archiveInfo.getArtifactId();
        String type = archiveInfo.getExtension() != null ? archiveInfo.getExtension() : "";
        String version = archiveInfo.getVersion();
        String classifier = archiveInfo.getClassifier() != null ? archiveInfo.getClassifier() : "";
        MavenArtifact mavenArtifact = new MavenArtifact();

        mavenArtifact.setGroupId(groupId);
        mavenArtifact.setArtifactId(artifactId);
        mavenArtifact.setType(type);
        mavenArtifact.setVersion(version);
        mavenArtifact.setClassifier(classifier);

        return mavenArtifact;
    }

    private NpmArtifact createNpmArtifact(KojiArchiveInfo archiveInfo) {
        String name = archiveInfo.getArtifactId();
        String version = archiveInfo.getVersion();
        NpmArtifact npmArtifact = new NpmArtifact();

        npmArtifact.setName(name);
        npmArtifact.setVersion(version);

        return npmArtifact;
    }

    private Artifact createNotFoundArtifact(KojiLocalArchive localArchive) {
        Artifact artifact = new Artifact();

        setArtifactChecksums(artifact, localArchive.getChecksums());

        artifact.setBuiltFromSource(Boolean.FALSE);
        artifact.setFilesNotBuiltFromSource(new TreeSet<>(localArchive.getFilenames()));

        return artifact;
    }

    private Set<Artifact> getNotFoundArtifacts(Map<BuildSystemInteger, KojiBuild> builds) {
        int buildsSize = builds.size();

        if (buildsSize == 0) {
            return Collections.unmodifiableSet(new LinkedHashSet<>());
        }

        KojiBuild buildZero = builds.get(new BuildSystemInteger(0));
        List<KojiLocalArchive> localArchives = buildZero.getArchives();
        int numArchives = localArchives.size();

        if (numArchives == 0) {
            return Collections.unmodifiableSet(new LinkedHashSet<>());
        }

        Set<Artifact> artifacts = new LinkedHashSet<>(numArchives);
        int archiveCount = 0;

        for (KojiLocalArchive localArchive : localArchives) {
            Artifact artifact = createNotFoundArtifact(localArchive);

            artifacts.add(artifact);

            archiveCount++;

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Not found artifact: {} / {} ({})",
                        archiveCount,
                        numArchives,
                        artifact.getFilesNotBuiltFromSource());
            }
        }

        return Collections.unmodifiableSet(artifacts);
    }

    private Build createBuild(BuildSystemInteger buildSystemInteger, KojiBuild kojiBuild) {
        BuildSystemType buildSystemType;

        if (buildSystemInteger.getBuildSystem().equals(BuildSystem.pnc)) {
            buildSystemType = BuildSystemType.PNC;
        } else {
            buildSystemType = BuildSystemType.KOJI;
        }

        String identifier = kojiBuild.getBuildInfo().getNvr();
        Build build = new Build();

        build.setIdentifier(identifier);
        build.setBuildSystemType(buildSystemType);

        if (build.getBuildSystemType().equals(BuildSystemType.PNC)) {
            build.setPncId((long) kojiBuild.getBuildInfo().getId());
        } else {
            build.setKojiId((long) kojiBuild.getBuildInfo().getId());
        }

        build.setSource(kojiBuild.getSource());
        build.setBuiltFromSource(!kojiBuild.isImport());

        return build;
    }

    private Artifact createArtifact(KojiLocalArchive localArchive, Build build) {
        KojiArchiveInfo archiveInfo = localArchive.getArchive();
        MavenArtifact mavenArtifact = null;
        NpmArtifact npmArtifact = null;
        String artifactIdentifier;

        if (archiveInfo.getBuildType().equals("maven")) {
            mavenArtifact = createMavenArtifact(archiveInfo);
            artifactIdentifier = mavenArtifact.getIdentifier();
        } else if (archiveInfo.getBuildType().equals("npm")) {
            npmArtifact = createNpmArtifact(archiveInfo);
            artifactIdentifier = npmArtifact.getIdentifier();
        } else {
            throw new BadRequestException(
                    "Archive " + archiveInfo.getArtifactId() + " had unhandled artifact type: "
                            + archiveInfo.getBuildType());
        }

        Artifact artifact = new Artifact();

        artifact.setIdentifier(artifactIdentifier);
        artifact.setBuildSystemType(build.getBuildSystemType());

        if (!localArchive.isBuiltFromSource()) {
            artifact.setBuiltFromSource(Boolean.FALSE);
            artifact.getFilesNotBuiltFromSource().addAll(localArchive.getUnmatchedFilenames());
        } else {
            artifact.setBuiltFromSource(build.getBuiltFromSource());
        }

        setArtifactChecksums(artifact, localArchive.getChecksums());

        if (build.getBuildSystemType().equals(BuildSystemType.PNC)) {
            artifact.setPncId(Long.valueOf(archiveInfo.getArchiveId()));
        } else {
            artifact.setKojiId(Long.valueOf(archiveInfo.getArchiveId()));
        }

        artifact.setBuild(build);

        if (mavenArtifact != null) {
            mavenArtifact.setArtifact(artifact);
            artifact.setMavenArtifact(mavenArtifact);
            artifact.setType(Artifact.Type.MAVEN);
        } else {
            npmArtifact.setArtifact(artifact);
            artifact.setNpmArtifact(npmArtifact);
            artifact.setType(Artifact.Type.NPM);
        }

        return artifact;
    }

    private Set<Build> getFoundBuilds(Map<BuildSystemInteger, KojiBuild> builds) {
        int buildsSize = builds.size();

        if (buildsSize <= 1) {
            return Collections.unmodifiableSet(new LinkedHashSet<>());
        }

        int numBuilds = buildsSize - 1;
        Set<Build> buildList = new LinkedHashSet<>(numBuilds);
        int buildCount = 0;
        Set<Map.Entry<BuildSystemInteger, KojiBuild>> entrySet = builds.entrySet();

        for (Map.Entry<BuildSystemInteger, KojiBuild> entry : entrySet) {
            BuildSystemInteger buildSystemInteger = entry.getKey();

            if (buildSystemInteger.getValue().equals(0)) {
                continue;
            }

            KojiBuild kojiBuild = entry.getValue();
            Build build = createBuild(buildSystemInteger, kojiBuild);

            if (LOGGER.isInfoEnabled()) {
                buildCount++;

                LOGGER.info(
                        "Build: {} / {} ({}.{})",
                        buildCount,
                        numBuilds,
                        build.getIdentifier(),
                        build.getBuildSystemType());
            }

            List<KojiLocalArchive> localArchives = kojiBuild.getArchives();
            int numArchives = localArchives.size();
            int archiveCount = 0;

            for (KojiLocalArchive localArchive : localArchives) {
                Artifact artifact = createArtifact(localArchive, build);

                build.getArtifacts().add(artifact);

                if (LOGGER.isInfoEnabled()) {
                    archiveCount++;

                    LOGGER.info("Artifact: {} / {} ({})", archiveCount, numArchives, artifact.getIdentifier());
                }
            }

            buildList.add(build);
        }

        return Collections.unmodifiableSet(buildList);
    }

    private List<KojiBuild> getBuildsAsList(Map<BuildSystemInteger, KojiBuild> builds) {
        List<KojiBuild> kojiBuildList = new ArrayList<>(builds.values());

        kojiBuildList.sort(Comparator.comparingInt(KojiBuild::getId));

        return Collections.unmodifiableList(kojiBuildList);
    }
}
