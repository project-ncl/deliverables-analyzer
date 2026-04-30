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
package org.jboss.pnc.deliverablesanalyzer.utils;

import org.jboss.pnc.api.deliverablesanalyzer.dto.Artifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.Artifact.ArtifactBuilder;
import org.jboss.pnc.api.deliverablesanalyzer.dto.Build;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.deliverablesanalyzer.dto.LicenseInfo;
import org.jboss.pnc.api.deliverablesanalyzer.dto.MavenArtifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.MavenArtifact.MavenArtifactBuilder;
import org.jboss.pnc.api.deliverablesanalyzer.dto.NPMArtifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.NPMArtifact.NPMArtifactBuilder;
import org.jboss.pnc.api.deliverablesanalyzer.dto.RpmArtifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.RpmArtifact.RpmArtifactBuilder;
import org.jboss.pnc.api.deliverablesanalyzer.dto.WindowsArtifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.WindowsArtifact.WindowsArtifactBuilder;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.AnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerBuild;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.AnalyzerResult;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.MavenAnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.NpmAnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.RpmAnalyzerArtifact;
import org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact.WindowsAnalyzerArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class FinderResultCreator {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinderResultCreator.class);

    private FinderResultCreator() {
    }

    public static FinderResult createFinderResult(String id, String url, AnalyzerResult builds) {
        try {
            return FinderResult.builder()
                    .id(id)
                    .url(URI.create(url).normalize().toURL())
                    .notFoundArtifacts(getNotFoundArtifacts(builds.notFoundArtifacts()))
                    .builds(getFoundBuilds(builds.foundBuilds()))
                    .build();
        } catch (MalformedURLException e) {
            throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "Couldn't parse URL when creating result", e);
        }
    }

    private static MavenArtifactBuilder<?, ?> createMavenArtifact(MavenAnalyzerArtifact artifact) {
        MavenArtifactBuilder<?, ?> builder = MavenArtifact.builder();
        builder.groupId(artifact.getGroupId());
        builder.artifactId(artifact.getArtifactId());
        builder.type(artifact.getType());
        builder.version(artifact.getVersion());
        builder.classifier(artifact.getClassifier());
        return builder;
    }

    private static NPMArtifactBuilder<?, ?> createNpmArtifact(NpmAnalyzerArtifact artifact) {
        NPMArtifactBuilder<?, ?> builder = NPMArtifact.builder();
        builder.name(artifact.getName());
        builder.version(artifact.getVersion()); // How to determine version for npm artifacts from PNC?
        return builder;
    }

    private static WindowsArtifactBuilder<?, ?> createWindowsArtifact(WindowsAnalyzerArtifact artifact) {
        WindowsArtifactBuilder<?, ?> builder = WindowsArtifact.builder();
        builder.name(artifact.getName());
        builder.version(artifact.getVersion());
        builder.platforms(artifact.getPlatforms());
        builder.flags(artifact.getFlags());
        return builder;
    }

    private static RpmArtifactBuilder<?, ?> createRpmArtifact(RpmAnalyzerArtifact artifact) {
        RpmArtifactBuilder<?, ?> builder = RpmArtifact.builder();
        builder.name(artifact.getName());
        builder.version(artifact.getVersion());
        builder.release(artifact.getRelease());
        builder.arch(artifact.getArch());
        return builder;
    }

    private static Set<Artifact> getNotFoundArtifacts(Set<AnalyzerArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return Collections.emptySet();
        }

        int numberOfArtifacts = artifacts.size();
        Set<Artifact> notFoundArtifacts = new HashSet<>(numberOfArtifacts);
        int artifactIndex = 0;

        for (AnalyzerArtifact artifact : artifacts) {
            if (artifact.getFilenames() == null || artifact.getFilenames().isEmpty()) {
                throw new IllegalArgumentException("Filename for not-found artifact is missing. " + artifact);
            }
            Collection<Artifact> artifactCollection = createNotFoundArtifacts(artifact);
            notFoundArtifacts.addAll(artifactCollection);

            if (LOGGER.isDebugEnabled()) {
                artifactIndex += artifactCollection.size();
                LOGGER.debug(
                        "Not found artifact: {} / {} ({})",
                        artifactIndex,
                        numberOfArtifacts,
                        artifact.getFilenames());
            }
        }

        return notFoundArtifacts;
    }

    private static Collection<Artifact> createNotFoundArtifacts(AnalyzerArtifact artifact) {
        Collection<Artifact> artifacts = new HashSet<>();

        for (String filename : artifact.getFilenames()) {
            ArtifactBuilder<?, ?> builder = Artifact.builder();
            builder.builtFromSource(false);
            builder.filename(filename);
            builder.sha256(artifact.getChecksum().getSha256Value());
            builder.sha1(artifact.getChecksum().getSha1Value());
            builder.md5(artifact.getChecksum().getMd5Value());
            builder.size(artifact.getChecksum().getFileSize());

            setLicenseInformation(builder, artifact.getLicenses());

            builder.archiveFilenames(artifact.getFilenames());
            builder.archiveUnmatchedFilenames(artifact.getUnmatchedFilenames());

            artifacts.add(builder.build());
        }

        return artifacts;
    }

    private static Set<Build> getFoundBuilds(Map<String, AnalyzerBuild> builds) {
        if (builds == null || builds.isEmpty()) {
            return Collections.emptySet();
        }

        int numberOfBuilds = builds.size();
        Set<Build> foundBuilds = new LinkedHashSet<>(numberOfBuilds);
        int buildIndex = 0;

        for (Map.Entry<String, AnalyzerBuild> entry : builds.entrySet()) {
            AnalyzerBuild analyzerBuild = entry.getValue();
            List<AnalyzerArtifact> artifacts = analyzerBuild.getBuiltArtifacts();

            int numberOfArtifacts = artifacts.size();
            Set<Artifact> foundArtifacts = new HashSet<>(numberOfArtifacts);
            int artifactIndex = 0;

            for (AnalyzerArtifact analyzerArtifact : artifacts) {
                Artifact artifact = createFoundArtifact(analyzerArtifact, analyzerBuild.isImport());
                foundArtifacts.add(artifact);

                if (LOGGER.isDebugEnabled()) {
                    artifactIndex++;
                    LOGGER.debug(
                            "Artifact: {} / {} ({})",
                            artifactIndex,
                            numberOfArtifacts,
                            getIdentifier(artifact.getBuildSystemType(), artifact.getPncId(), artifact.getBrewId()));
                }
            }

            Build build = createBuild(analyzerBuild, foundArtifacts);
            foundBuilds.add(build);
            buildIndex++;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Build: {} / {} ({})",
                        buildIndex,
                        numberOfBuilds,
                        getIdentifier(build.getBuildSystemType(), build.getPncId(), build.getBrewId()));
            }
        }

        return foundBuilds;
    }

    private static Artifact createFoundArtifact(AnalyzerArtifact artifact, boolean isImport) {
        ArtifactBuilder<?, ?> builder = switch (artifact) {
            case MavenAnalyzerArtifact maven -> createMavenArtifact(maven);
            case NpmAnalyzerArtifact npm -> createNpmArtifact(npm);
            case WindowsAnalyzerArtifact win -> createWindowsArtifact(win);
            case RpmAnalyzerArtifact rpm -> createRpmArtifact(rpm);
            default -> Artifact.builder();
        };

        builder.buildSystemType(artifact.getBuildSystemType());
        switch (artifact.getBuildSystemType()) {
            case PNC -> builder.pncId(artifact.getSystemArtifactId());
            case BREW -> builder.brewId(Long.valueOf(artifact.getSystemArtifactId()));
            default -> throw new IllegalArgumentException("Unknown build system type: " + artifact.getBuildSystemType());
        }

        builder.builtFromSource(artifact.getUnmatchedFilenames().isEmpty() && !isImport);

        builder.sha256(artifact.getChecksum().getSha256Value());
        builder.sha1(artifact.getChecksum().getSha1Value());
        builder.md5(artifact.getChecksum().getMd5Value());
        builder.filename(artifact.getArtifactFilename());
        builder.size(artifact.getArtifactSize());

        setLicenseInformation(builder, artifact.getLicenses());

        builder.archiveFilenames(artifact.getFilenames());
        builder.archiveUnmatchedFilenames(artifact.getUnmatchedFilenames());

        return builder.build();
    }

    private static Build createBuild(AnalyzerBuild analyzerBuild, Set<Artifact> artifacts) {
        Build.Builder builder = Build.builder();
        BuildSystemType buildSystemType = analyzerBuild.getBuildSystemType();
        builder.buildSystemType(buildSystemType);

        if (buildSystemType == BuildSystemType.BREW) {
            builder.brewId(Long.valueOf(analyzerBuild.getBuildId()));
            builder.brewNVR(analyzerBuild.getBuildNvr());
        } else {
            builder.pncId(analyzerBuild.getBuildId());
        }

        builder.isImport(analyzerBuild.isImport());
        builder.artifacts(artifacts);
        return builder.build();
    }

    private static void setLicenseInformation(
            ArtifactBuilder<?, ?> builder,
            List<org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo> licenseInfos) {
        Set<LicenseInfo> licenses = Optional.ofNullable(licenseInfos)
                .orElse(Collections.emptyList())
                .stream()
                .map(FinderResultCreator::toLicenseInfoDTO)
                .collect(Collectors.toSet());

        builder.licenses(licenses);
    }

    private static LicenseInfo toLicenseInfoDTO(org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo license) {
        LicenseInfo.LicenseInfoBuilder licenseBuilder = LicenseInfo.builder()
                .comments(license.getComments())
                .distribution(license.getDistribution())
                .name(license.getName())
                .spdxLicenseId(license.getSpdxLicenseId())
                .url(license.getUrl())
                .sourceUrl(license.getSourceUrl());
        return licenseBuilder.build();
    }

    private static String getIdentifier(BuildSystemType buildSystemType, String pncId, Long brewId) {
        return switch (buildSystemType) {
            case PNC -> "PNC#" + pncId;
            case BREW -> "Brew#" + brewId;
        };
    }
}
