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
package org.jboss.pnc.deliverablesanalyzer.model.analyzer.artifact;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBtype;
import com.redhat.red.build.koji.model.xmlrpc.KojiRpmInfo;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.KojiBuild;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.enums.BuildType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AnalyzerArtifactMapper {

    private static final Pattern RPM_PATTERN = Pattern.compile("^(.*?)-([^-]+)-(.*?)\\.([^.]+)(?:\\.rpm)?$");

    private AnalyzerArtifactMapper() {
        // Prevent instantiation
    }

    public static AnalyzerArtifact mapFromNotFound(
            Checksum checksum,
            Collection<String> filenames,
            List<LicenseInfo> licenses,
            String inputPath) {
        AnalyzerArtifact artifact = new AnalyzerArtifact();
        populateBaseProperties(artifact, null, inputPath, checksum, filenames, licenses);
        return artifact;
    }

    public static AnalyzerArtifact mapFromPnc(Artifact pncArtifact, Checksum checksum, Collection<String> filenames, List<LicenseInfo> licenses, String inputPath) {
        AnalyzerArtifact artifact;

        if (pncArtifact != null && pncArtifact.getBuild() != null) {
            BuildType buildType = pncArtifact.getBuild().getBuildConfigRevision().getBuildType();

            artifact = switch (buildType) {
                case GRADLE, MVN, MVN_RPM, SBT -> {
                    MavenAnalyzerArtifact maven = new MavenAnalyzerArtifact();
                    String[] gaecv = pncArtifact.getIdentifier().split(":");
                    if (gaecv.length >= 3) {
                        maven.setGroupId(gaecv[0]);
                        maven.setArtifactId(gaecv[1]);
                        maven.setType(gaecv[2]);
                        maven.setVersion(gaecv[3]);
                        maven.setClassifier(gaecv.length > 4 ? gaecv[4] : null);
                    }
                    yield maven;
                }
                case NPM -> {
                    NpmAnalyzerArtifact npm = new NpmAnalyzerArtifact();
                    String identifier = pncArtifact.getIdentifier();
                    int lastAtIndex = identifier.lastIndexOf('@');
                    if (lastAtIndex > 0) {
                        npm.setName(identifier.substring(0, lastAtIndex));
                        npm.setVersion(identifier.substring(lastAtIndex + 1));
                    } else {
                        npm.setName(identifier);
                        npm.setVersion("unknown");
                    }
                    yield npm;
                }
                case RPM -> {
                    RpmAnalyzerArtifact rpm = new RpmAnalyzerArtifact();
                    String identifier = pncArtifact.getIdentifier();
                    Matcher matcher = RPM_PATTERN.matcher(identifier);
                    if (matcher.matches()) {
                        rpm.setName(matcher.group(1));
                        rpm.setVersion(matcher.group(2));
                        rpm.setRelease(matcher.group(3));
                        rpm.setArch(matcher.group(4));
                    } else {
                        rpm.setName(identifier);
                        rpm.setVersion("unknown");
                    }
                    yield rpm;
                }
            };
        } else {
            artifact = new AnalyzerArtifact();
        }

        populateBaseProperties(artifact, BuildSystemType.PNC, inputPath, checksum, filenames, licenses);

        if (pncArtifact != null && pncArtifact.getBuild() != null) {
            artifact.setBuildId(pncArtifact.getBuild().getId());
            artifact.setArtifactId(pncArtifact.getId());
            artifact.setArtifactFilename(pncArtifact.getFilename());
            artifact.setArtifactSize(pncArtifact.getSize());
            artifact.setImport(pncArtifact.getBuild().getScmRepository() == null || pncArtifact.getBuild().getScmRepository().getInternalUrl() == null);
        }

        return artifact;
    }

    public static AnalyzerArtifact mapFromKojiArchive(KojiArchiveInfo archiveInfo, KojiBuild buildDetails, Checksum checksum, Collection<String> filenames, List<LicenseInfo> licenses, String inputPath) {
        AnalyzerArtifact artifact;

        if (archiveInfo != null && archiveInfo.getBuildType() != null) {
            KojiBtype buildType = archiveInfo.getBuildType();
            artifact = switch (buildType) {
                case maven -> {
                    MavenAnalyzerArtifact maven = new MavenAnalyzerArtifact();
                    maven.setGroupId(archiveInfo.getGroupId());
                    maven.setArtifactId(archiveInfo.getArtifactId());
                    maven.setType(archiveInfo.getExtension());
                    maven.setVersion(archiveInfo.getVersion());
                    maven.setClassifier(archiveInfo.getClassifier());
                    yield maven;
                }
                case npm -> {
                    NpmAnalyzerArtifact npm = new NpmAnalyzerArtifact();
                    npm.setName(archiveInfo.getArtifactId());
                    npm.setVersion(archiveInfo.getVersion());
                    yield npm;
                }
                case win -> {
                    WindowsAnalyzerArtifact win = new WindowsAnalyzerArtifact();
                    win.setName(archiveInfo.getArtifactId());
                    String release = (buildDetails != null && buildDetails.getInfo() != null && buildDetails.getInfo().getRelease() != null) ? buildDetails.getInfo().getRelease() : "unknown";
                    win.setVersion(archiveInfo.getVersion() + "-" + release);
                    win.setPlatforms(archiveInfo.getPlatforms());
                    win.setFlags(archiveInfo.getFlags());
                    yield win;
                }
                default -> throw new IllegalArgumentException("Artifact build type " + buildType + " not handled");
            };
        } else {
            artifact = new AnalyzerArtifact();
        }

        populateBaseProperties(artifact, BuildSystemType.BREW, inputPath, checksum, filenames, licenses);

        if (archiveInfo != null && archiveInfo.getBuildId() != null) {
            artifact.setBuildId(String.valueOf(archiveInfo.getBuildId()));
            artifact.setArtifactId(archiveInfo.getArchiveId().toString());
            artifact.setArtifactFilename(archiveInfo.getFilename());
            artifact.setArtifactSize(Long.valueOf(archiveInfo.getSize()));

            if (buildDetails != null && buildDetails.getInfo() != null) {
                artifact.setBuildNvr(buildDetails.getInfo().getNvr());
                artifact.setImport(buildDetails.getInfo().getTaskId() == null);
            }
        }

        return artifact;
    }

    public static RpmAnalyzerArtifact mapFromKojiRpm(
            KojiRpmInfo rpm,
            KojiBuild buildDetails,
            Checksum checksum,
            Collection<String> filenames,
            List<LicenseInfo> licenses,
            String inputPath) {
        RpmAnalyzerArtifact artifact = new RpmAnalyzerArtifact();
        populateBaseProperties(artifact, BuildSystemType.BREW, inputPath, checksum, filenames, licenses);

        if (rpm != null && rpm.getBuildId() != null) {
            artifact.setName(rpm.getName());
            artifact.setVersion(rpm.getVersion());
            artifact.setRelease(rpm.getRelease());
            artifact.setArch(rpm.getArch());

            artifact.setBuildId(String.valueOf(rpm.getBuildId()));
            artifact.setArtifactId(String.valueOf(rpm.getId()));
            artifact.setArtifactFilename(
                    rpm.getName() + "-" + rpm.getVersion() + "-" + rpm.getRelease() + "." + rpm.getArch() + ".rpm");
            artifact.setArtifactSize(rpm.getSize());

            if (buildDetails != null && buildDetails.getInfo() != null) {
                artifact.setBuildNvr(buildDetails.getInfo().getNvr());
                artifact.setImport(buildDetails.getInfo().getTaskId() == null);
            }
        }

        return artifact;
    }

    private static void populateBaseProperties(
            AnalyzerArtifact artifact,
            BuildSystemType systemType,
            String inputPath,
            Checksum checksum,
            Collection<String> filenames,
            List<LicenseInfo> licenses) {
        artifact.setBuildSystemType(systemType);
        artifact.setInputPath(inputPath);
        artifact.setChecksum(checksum);
        artifact.setFilenames(filenames != null ? new ArrayList<>(filenames) : new ArrayList<>());
        artifact.setLicenses(licenses != null ? new ArrayList<>(licenses) : new ArrayList<>());
        artifact.setUnmatchedFilenames(new ArrayList<>());
    }
}
