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

import static org.jboss.pnc.api.deliverablesanalyzer.dto.Artifact.ArtifactBuilder;

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

import org.jboss.pnc.api.deliverablesanalyzer.dto.Artifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.Build;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.deliverablesanalyzer.dto.LicenseInfo;
import org.jboss.pnc.api.deliverablesanalyzer.dto.MavenArtifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.MavenArtifact.MavenArtifactBuilder;
import org.jboss.pnc.api.deliverablesanalyzer.dto.NPMArtifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.NPMArtifact.NPMArtifactBuilder;
import org.jboss.pnc.api.dto.exception.ReasonedException;
import org.jboss.pnc.api.enums.ResultStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FinderResultCreator {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinderResultCreator.class);

    private static final String BUILD_ID_ZERO = "0";

    private FinderResultCreator() {
    }

    public static FinderResult createFinderResult(String id, String url, Map<String, PncBuild> builds) {
        PncBuild buildZero = builds.get(BUILD_ID_ZERO);

        try {
            return FinderResult.builder()
                    .id(id)
                    .url(URI.create(url).normalize().toURL())
                    .notFoundArtifacts(getNotFoundArtifacts(buildZero))
                    .builds(getFoundBuilds(builds))
                    .build();
        } catch (MalformedURLException e) {
            throw new ReasonedException(ResultStatus.SYSTEM_ERROR, "Couldn't parse URL when creating result", e);
        }
    }

    private static MavenArtifactBuilder<?, ?> createMavenArtifact(EnhancedArtifact artifact) {
        MavenArtifactBuilder<?, ?> builder = MavenArtifact.builder();

        String[] gaecv = artifact.getArtifact().getIdentifier().split(":");
        if (gaecv.length >= 3) {
            builder.groupId(gaecv[0]);
            builder.artifactId(gaecv[1]);
            builder.type(gaecv[2]);
            builder.version(gaecv[3]);
            builder.classifier(gaecv.length > 4 ? gaecv[4] : null);
        }

        return builder;
    }

    private static NPMArtifactBuilder<?, ?> createNpmArtifact(EnhancedArtifact artifact) {
        return NPMArtifact.builder().name(artifact.getArtifact().getId());
        // .version(---); // How to determine version for npm artifacts?
    }

    private static Set<Artifact> getNotFoundArtifacts(PncBuild buildZero) {
        if (buildZero == null) {
            return Collections.emptySet();
        }

        List<EnhancedArtifact> artifacts = buildZero.getBuiltArtifacts();
        if (artifacts == null || artifacts.isEmpty()) {
            return Collections.emptySet();
        }

        int numberOfArtifacts = artifacts.size();
        Set<Artifact> notFoundArtifacts = new HashSet<>(numberOfArtifacts);
        int artifactIndex = 0;

        for (EnhancedArtifact artifact : artifacts) {
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

    private static Collection<Artifact> createNotFoundArtifacts(EnhancedArtifact artifact) {
        Collection<Artifact> artifacts = new HashSet<>();

        for (String filename : artifact.getFilenames()) {
            ArtifactBuilder<?, ?> builder = Artifact.builder();
            builder.builtFromSource(false);
            builder.filename(filename); // not found?
            builder.sha256(artifact.getChecksum().getValue());
            builder.size(artifact.getChecksum().getFileSize());

            setLicenseInformation(builder, artifact.getLicenses());

            builder.archiveFilenames(List.of(filename));
            builder.archiveUnmatchedFilenames(artifact.getUnmatchedFilenames());

            artifacts.add(builder.build());
        }

        return artifacts;
    }

    private static Set<Build> getFoundBuilds(Map<String, PncBuild> builds) {
        if (builds == null || builds.isEmpty()) {
            return Collections.emptySet();
        }

        int numberOfBuilds = builds.size() - 1;
        Set<Build> foundBuilds = new LinkedHashSet<>(numberOfBuilds);
        int buildIndex = 0;

        for (Map.Entry<String, PncBuild> entry : builds.entrySet()) {
            if (entry.getKey().equals(BUILD_ID_ZERO)) {
                continue;
            }

            PncBuild pncBuild = entry.getValue();
            List<EnhancedArtifact> artifacts = pncBuild.getBuiltArtifacts();

            int numberOfArtifacts = artifacts.size();
            Set<Artifact> foundArtifacts = new HashSet<>(numberOfArtifacts);
            int artifactIndex = 0;

            for (EnhancedArtifact enhancedArtifact : artifacts) {
                Artifact artifact = createFoundArtifact(enhancedArtifact, pncBuild.isImport());
                foundArtifacts.add(artifact);

                if (LOGGER.isDebugEnabled()) {
                    artifactIndex++;
                    LOGGER.debug(
                            "Artifact: {} / {} ({})",
                            artifactIndex,
                            numberOfArtifacts,
                            getIdentifier(artifact.getPncId()));
                }
            }

            Build build = createBuild(pncBuild, foundArtifacts);
            foundBuilds.add(build);
            buildIndex++;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Build: {} / {} ({})", buildIndex, numberOfBuilds, getIdentifier(build.getPncId()));
            }
        }

        return foundBuilds;
    }

    private static Artifact createFoundArtifact(EnhancedArtifact artifacts, boolean isImport) {
        ArtifactBuilder<?, ?> builder = switch (artifacts.getArtifact()
                .getBuild()
                .getBuildConfigRevision()
                .getBuildType()) {
            case GRADLE, MVN, MVN_RPM, SBT -> createMavenArtifact(artifacts);
            case NPM -> createNpmArtifact(artifacts);
        };
        builder.buildSystemType(BuildSystemType.PNC);
        builder.pncId(artifacts.getArtifact().getId());
        builder.builtFromSource(artifacts.getUnmatchedFilenames().isEmpty() && !isImport); // todo tomas: check

        builder.sha256(artifacts.getChecksum().getValue());
        builder.filename(artifacts.getArtifact().getFilename());
        builder.size(artifacts.getArtifact().getSize());

        setLicenseInformation(builder, artifacts.getLicenses());

        builder.archiveFilenames(artifacts.getFilenames());
        builder.archiveUnmatchedFilenames(artifacts.getUnmatchedFilenames());

        return builder.build();
    }

    private static Build createBuild(PncBuild pncBuild, Set<Artifact> artifacts) {
        Build.Builder builder = Build.builder();
        builder.buildSystemType(BuildSystemType.PNC);
        builder.pncId(pncBuild.getBuild().getId());
        builder.isImport(pncBuild.isImport());
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

    private static String getIdentifier(String pncId) {
        return "PNC#" + pncId;
    }
}
