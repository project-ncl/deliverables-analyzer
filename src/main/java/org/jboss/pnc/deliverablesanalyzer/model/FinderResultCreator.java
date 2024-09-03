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

import static org.jboss.pnc.build.finder.core.BuildFinderUtils.isBuildIdZero;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.BadRequestException;

import org.jboss.pnc.api.deliverablesanalyzer.dto.Artifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.Build;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.FinderResult;
import org.jboss.pnc.api.deliverablesanalyzer.dto.LicenseInfo;
import org.jboss.pnc.api.deliverablesanalyzer.dto.MavenArtifact;
import org.jboss.pnc.api.deliverablesanalyzer.dto.NPMArtifact;
import org.jboss.pnc.api.enums.LicenseSource;
import org.jboss.pnc.build.finder.core.BuildSystem;
import org.jboss.pnc.build.finder.core.BuildSystemInteger;
import org.jboss.pnc.build.finder.core.Checksum;
import org.jboss.pnc.build.finder.koji.KojiBuild;
import org.jboss.pnc.build.finder.koji.KojiLocalArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;

public final class FinderResultCreator {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinderResultCreator.class);

    private FinderResultCreator() {
    }

    public static FinderResult createFinderResult(String id, URL url, Map<BuildSystemInteger, KojiBuild> builds) {
        return FinderResult.builder()
                .id(id)
                .url(url)
                .notFoundArtifacts(getNotFoundArtifacts(builds))
                .builds(getFoundBuilds(builds))
                .build();
    }

    private static void setLicenseInformation(Artifact.ArtifactBuilder builder, KojiLocalArchive localArchive) {
        Set<LicenseInfo> licenses = Optional.ofNullable(localArchive.getLicenses())
                .orElse(Collections.emptySet())
                .stream()
                .map(FinderResultCreator::toDTO)
                .collect(Collectors.toSet());

        builder.licenses(licenses);
    }

    private static LicenseInfo toDTO(org.jboss.pnc.build.finder.core.LicenseInfo license) {
        LicenseInfo.LicenseInfoBuilder licenseBuilder = LicenseInfo.builder()
                .comments(license.getComments())
                .distribution(license.getDistribution())
                .name(license.getName())
                .spdxLicenseId(license.getSpdxLicenseId())
                .url(license.getUrl());

        org.jboss.pnc.build.finder.core.LicenseSource source = license.getSource();
        if (source == null) {
            throw new IllegalArgumentException("License source cannot be null");
        }

        switch (source) {
            case UNKNOWN:
                licenseBuilder.source(LicenseSource.UNKNOWN);
                break;
            case POM:
                licenseBuilder.source(LicenseSource.POM);
                break;
            case POM_XML:
                licenseBuilder.source(LicenseSource.POM_XML);
                break;
            case BUNDLE_LICENSE:
                licenseBuilder.source(LicenseSource.BUNDLE_LICENSE);
                break;
            case TEXT:
                licenseBuilder.source(LicenseSource.TEXT);
                break;
            default:
                throw new IllegalArgumentException("Unknown license source " + source);
        }

        return licenseBuilder.build();
    }

    private static void setCommonArtifactFields(Artifact.ArtifactBuilder builder, KojiLocalArchive archive) {
        KojiArchiveInfo archiveInfo = archive.getArchive();
        long size = archiveInfo.getSize();

        builder.filename(archiveInfo.getFilename()).size(size);

        for (Checksum checksum : archive.getChecksums()) {
            switch (checksum.getType()) {
                case md5:
                    builder.md5(checksum.getValue());
                    break;
                case sha1:
                    builder.sha1(checksum.getValue());
                    break;
                case sha256:
                    builder.sha256(checksum.getValue());
                    break;
                default:
                    break;
            }
        }
    }

    private static MavenArtifact.MavenArtifactBuilder createMavenArtifact(KojiArchiveInfo archiveInfo) {
        return MavenArtifact.builder()
                .groupId(archiveInfo.getGroupId())
                .artifactId(archiveInfo.getArtifactId())
                .type(archiveInfo.getExtension())
                .version(archiveInfo.getVersion())
                .classifier(archiveInfo.getClassifier());
    }

    private static NPMArtifact.NPMArtifactBuilder createNpmArtifact(KojiArchiveInfo archiveInfo) {
        return NPMArtifact.builder().name(archiveInfo.getArtifactId()).version(archiveInfo.getVersion());
    }

    private static Collection<Artifact> createNotFoundArtifacts(KojiLocalArchive localArchive) {
        Collection<Artifact> artifacts = new ArrayList<>();

        if (localArchive.getFilenames() == null || localArchive.getFilenames().isEmpty()) {
            throw new IllegalArgumentException("Filename for not-found artifact is missing. " + localArchive);
        }

        for (String filename : localArchive.getFilenames()) {
            Artifact.ArtifactBuilder<?, ?> builder = Artifact.builder().builtFromSource(false);
            localArchive.getArchive().setFilename(filename);

            setCommonArtifactFields(builder, localArchive);
            // Add the new license information provided by Build Finder
            setLicenseInformation(builder, localArchive);

            builder.archiveFilenames(List.of(filename)).archiveUnmatchedFilenames(localArchive.getUnmatchedFilenames());

            artifacts.add(builder.build());
        }

        return artifacts;
    }

    private static Set<Artifact> getNotFoundArtifacts(Map<BuildSystemInteger, KojiBuild> builds) {
        int buildsSize = builds.size();

        if (buildsSize == 0) {
            return Collections.unmodifiableSet(new LinkedHashSet<>());
        }

        KojiBuild buildZero = builds.get(new BuildSystemInteger(0));
        if (buildZero == null) {
            return Collections.unmodifiableSet(new LinkedHashSet<>());
        }

        List<KojiLocalArchive> localArchives = buildZero.getArchives();
        if (localArchives == null || localArchives.size() == 0) {
            return Collections.unmodifiableSet(new LinkedHashSet<>());
        }

        int numArchives = localArchives.size();
        Set<Artifact> artifacts = new LinkedHashSet<>(numArchives);
        int archiveCount = 0;

        for (KojiLocalArchive localArchive : localArchives) {
            Collection<Artifact> notFoundArtifacts = createNotFoundArtifacts(localArchive);
            artifacts.addAll(notFoundArtifacts);

            if (LOGGER.isDebugEnabled()) {
                archiveCount += notFoundArtifacts.size();
                LOGGER.debug(
                        "Not found artifact: {} / {} ({})",
                        archiveCount,
                        numArchives,
                        localArchive.getFilenames());
            }
        }

        return Collections.unmodifiableSet(artifacts);
    }

    private static Build createBuild(
            BuildSystemInteger buildSystemInteger,
            KojiBuild kojiBuild,
            Set<Artifact> artifacts) {
        Build.Builder builder = Build.builder();
        if (buildSystemInteger.getBuildSystem() == BuildSystem.pnc) {
            builder.buildSystemType(BuildSystemType.PNC);
            builder.pncId(kojiBuild.getId());
        } else {
            builder.buildSystemType(BuildSystemType.BREW);
            builder.brewId((long) kojiBuild.getBuildInfo().getId());
            builder.brewNVR(kojiBuild.getBuildInfo().getNvr());
        }
        return builder.isImport(kojiBuild.isImport()).artifacts(artifacts).build();
    }

    private static Artifact createArtifact(KojiLocalArchive localArchive, BuildSystem buildSystem, boolean imported) {
        KojiArchiveInfo archiveInfo = localArchive.getArchive();

        Artifact.ArtifactBuilder builder;
        if ("maven".equals(archiveInfo.getBuildType()) || "gradle".equals(archiveInfo.getBuildType())) {
            builder = createMavenArtifact(archiveInfo);
        } else if ("npm".equals(archiveInfo.getBuildType())) {
            builder = createNpmArtifact(archiveInfo);
        } else {
            throw new BadRequestException(
                    "Archive " + archiveInfo.getArtifactId() + " had unhandled artifact type: "
                            + archiveInfo.getBuildType());
        }

        switch (buildSystem) {
            case pnc:
                builder.buildSystemType(BuildSystemType.PNC);
                builder.pncId(archiveInfo.getArchiveId().toString());
                break;
            case koji:
                builder.buildSystemType(BuildSystemType.BREW);
                builder.brewId(archiveInfo.getArchiveId().longValue());
                break;
            default:
                throw new IllegalArgumentException("Unknown build system " + buildSystem);
        }
        builder.builtFromSource(localArchive.isBuiltFromSource() && !imported);

        setCommonArtifactFields(builder, localArchive);
        // Add the new license information provided by Build Finder
        setLicenseInformation(builder, localArchive);

        builder.archiveFilenames(localArchive.getFilenames())
                .archiveUnmatchedFilenames(localArchive.getUnmatchedFilenames());

        return builder.build();
    }

    private static Set<Build> getFoundBuilds(Map<BuildSystemInteger, KojiBuild> builds) {
        int buildsSize = builds.size();

        if (buildsSize <= 1) {
            return Collections.unmodifiableSet(new LinkedHashSet<>());
        }

        int numBuilds = buildsSize - 1;
        Set<Build> buildList = new LinkedHashSet<>(numBuilds);
        int buildCount = 0;

        for (Map.Entry<BuildSystemInteger, KojiBuild> entry : builds.entrySet()) {
            BuildSystemInteger buildSystemInteger = entry.getKey();

            if (isBuildIdZero(buildSystemInteger.getValue())) {
                continue;
            }

            KojiBuild kojiBuild = entry.getValue();
            List<KojiLocalArchive> localArchives = kojiBuild.getArchives();

            int numArchives = localArchives.size();
            int archiveCount = 0;

            Set<Artifact> artifacts = new HashSet<>();

            for (KojiLocalArchive localArchive : localArchives) {
                Artifact artifact = createArtifact(
                        localArchive,
                        buildSystemInteger.getBuildSystem(),
                        kojiBuild.isImport());

                artifacts.add(artifact);

                if (LOGGER.isDebugEnabled()) {
                    archiveCount++;
                    String identifier = getIdentifier(
                            artifact.getBuildSystemType(),
                            artifact.getBrewId(),
                            artifact.getPncId());
                    LOGGER.debug("Artifact: {} / {} ({})", archiveCount, numArchives, identifier);
                }
            }

            Build build = createBuild(buildSystemInteger, kojiBuild, artifacts);

            if (LOGGER.isDebugEnabled()) {
                buildCount++;
                String identifier = getIdentifier(build.getBuildSystemType(), build.getBrewId(), build.getPncId());
                LOGGER.debug("Build: {} / {} ({})", buildCount, numBuilds, identifier);
            }

            buildList.add(build);
        }

        return Collections.unmodifiableSet(buildList);
    }

    private static String getIdentifier(BuildSystemType buildSystemType, Long brewId, String pncId) {
        String identifier;
        switch (buildSystemType) {
            case BREW:
                identifier = "Brew#" + Objects.requireNonNullElse(brewId, "-1");
                break;
            case PNC:
                identifier = "PNC#" + pncId;
                break;
            default:
                identifier = "Unknown#-1";
                break;
        }
        return identifier;
    }
}
