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
package org.jboss.pnc.deliverablesanalyzer.model.analyzer;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBtype;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jboss.pnc.api.deliverablesanalyzer.dto.ArtifactType;
import org.jboss.pnc.api.deliverablesanalyzer.dto.BuildSystemType;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.KojiBuild;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.enums.BuildType;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Artifact entity with additional information needed for analysis
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzerArtifact {

    private BuildSystemType buildSystemType;
    private String inputPath;
    private Checksum checksum;

    private Collection<String> filenames = new ArrayList<>();
    private Collection<String> unmatchedFilenames = new ArrayList<>();
    private List<LicenseInfo> licenses = Collections.emptyList();

    // Build Properties
    private String buildId;
    private String buildNvr;
    private boolean isImport;

    // Artifact Properties
    private String artifactId;
    private String artifactFilename;
    private Long artifactSize;
    private ArtifactType artifactType;
    private Map<String, Object> artifactProps;

    public static AnalyzerArtifact fromNotFoundArtifact(
            Checksum checksum,
            Collection<String> filenames,
            List<LicenseInfo> licenses,
            String inputPath) {
        AnalyzerArtifact analyzerArtifact = new AnalyzerArtifact();
        analyzerArtifact.checksum = checksum;
        analyzerArtifact.filenames = filenames;
        analyzerArtifact.licenses = licenses;
        analyzerArtifact.inputPath = inputPath;
        return analyzerArtifact;
    }

    public static AnalyzerArtifact fromPncArtifact(Artifact artifact, Checksum checksum, Collection<String> filenames, List<LicenseInfo> licenses, String inputPath) {
        AnalyzerArtifact analyzerArtifact = new AnalyzerArtifact();
        analyzerArtifact.buildSystemType = BuildSystemType.PNC;
        analyzerArtifact.inputPath = inputPath;
        analyzerArtifact.checksum = checksum;
        analyzerArtifact.filenames = filenames;
        analyzerArtifact.licenses = licenses;

        if (artifact != null && artifact.getBuild() != null) {
            analyzerArtifact.setBuildId(artifact.getBuild().getId());
            analyzerArtifact.setArtifactId(artifact.getId());
            analyzerArtifact.setArtifactFilename(artifact.getFilename());
            analyzerArtifact.setArtifactSize(artifact.getSize());
            analyzerArtifact.setArtifactProps(new HashMap<>());

            analyzerArtifact.setImport(artifact.getBuild().getScmRepository() == null || artifact.getBuild().getScmRepository().getInternalUrl() == null);

            BuildType buildType = artifact.getBuild().getBuildConfigRevision().getBuildType();
            switch (buildType) {
                case GRADLE, MVN, MVN_RPM, SBT -> {
                    analyzerArtifact.setArtifactType(ArtifactType.MAVEN);
                    String[] gaecv = artifact.getIdentifier().split(":");
                    if (gaecv.length >= 3) {
                        analyzerArtifact.getArtifactProps().put("groupId", gaecv[0]);
                        analyzerArtifact.getArtifactProps().put("artifactId", gaecv[1]);
                        analyzerArtifact.getArtifactProps().put("type", gaecv[2]);
                        analyzerArtifact.getArtifactProps().put("version", gaecv[3]);
                        analyzerArtifact.getArtifactProps().put("classifier", gaecv.length > 4 ? gaecv[4] : null);
                    }
                }
                case NPM -> {
                    analyzerArtifact.setArtifactType(ArtifactType.NPM);
                    String identifier = artifact.getIdentifier();
                    int lastAtIndex = identifier.lastIndexOf('@');

                    if (lastAtIndex > 0) {
                        analyzerArtifact.getArtifactProps().put("name", identifier.substring(0, lastAtIndex));
                        analyzerArtifact.getArtifactProps().put("version", identifier.substring(lastAtIndex + 1));
                    } else {
                        analyzerArtifact.getArtifactProps().put("name", identifier);
                        analyzerArtifact.getArtifactProps().put("version", "unknown");
                    }
                    analyzerArtifact.getArtifactProps().put("name", artifact.getId());
                    analyzerArtifact.getArtifactProps().put("version", artifact.getIdentifier());
                }
                default -> throw new BadRequestException("Artifact build type " + buildType + " not handled");
            }
        }

        return analyzerArtifact;
    }

    public static AnalyzerArtifact fromKojiArchive(KojiArchiveInfo artifact, KojiBuild buildDetails, Checksum checksum, Collection<String> filenames, List<LicenseInfo> licenses, String inputPath) {
        AnalyzerArtifact analyzerArtifact = new AnalyzerArtifact();
        analyzerArtifact.buildSystemType = BuildSystemType.BREW;
        analyzerArtifact.inputPath = inputPath;
        analyzerArtifact.checksum = checksum;
        analyzerArtifact.filenames = filenames;
        analyzerArtifact.licenses = licenses;

        if (artifact != null && artifact.getBuildId() != null) {
            analyzerArtifact.setBuildId(String.valueOf(artifact.getBuildId()));
            analyzerArtifact.setArtifactId(artifact.getArchiveId().toString());
            analyzerArtifact.setArtifactFilename(artifact.getFilename());
            analyzerArtifact.setArtifactSize(Long.valueOf(artifact.getSize()));
            analyzerArtifact.setArtifactProps(new HashMap<>());

            if (buildDetails != null && buildDetails.getInfo() != null) {
                analyzerArtifact.setBuildNvr(buildDetails.getInfo().getNvr());
                analyzerArtifact.setImport(buildDetails.getInfo().getTaskId() == null);
            }

            KojiBtype buildType = artifact.getBuildType();
            switch (buildType) {
                case maven -> {
                    analyzerArtifact.setArtifactType(ArtifactType.MAVEN);
                    analyzerArtifact.getArtifactProps().put("groupId", artifact.getGroupId());
                    analyzerArtifact.getArtifactProps().put("artifactId", artifact.getArtifactId());
                    analyzerArtifact.getArtifactProps().put("type", artifact.getExtension());
                    analyzerArtifact.getArtifactProps().put("version", artifact.getVersion());
                    analyzerArtifact.getArtifactProps().put("classifier", artifact.getClassifier());
                }
                case npm -> {
                    analyzerArtifact.setArtifactType(ArtifactType.NPM);
                    analyzerArtifact.getArtifactProps().put("name", artifact.getArtifactId());
                    analyzerArtifact.getArtifactProps().put("version", artifact.getVersion());
                }
                case win -> {
                    analyzerArtifact.setArtifactType(ArtifactType.WINDOWS);
                    analyzerArtifact.getArtifactProps().put("name", artifact.getArtifactId());
                    String release = (buildDetails != null && buildDetails.getInfo() != null && buildDetails.getInfo().getRelease() != null)
                        ? buildDetails.getInfo().getRelease()
                        : "unknown";
                    analyzerArtifact.getArtifactProps().put("version", String.join("-", artifact.getVersion(), release));
                    analyzerArtifact.getArtifactProps().put("platforms", artifact.getPlatforms());
                    analyzerArtifact.getArtifactProps().put("flags", artifact.getFlags());
                }
                default -> throw new BadRequestException("Artifact build type " + buildType + " not handled");
            }
        }

        return analyzerArtifact;
    }
}
