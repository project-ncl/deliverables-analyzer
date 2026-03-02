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
package org.jboss.pnc.deliverablesanalyzer.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.vfs2.FileObject;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jboss.pnc.deliverablesanalyzer.utils.AnalyzerUtils;
import org.jboss.pnc.deliverablesanalyzer.utils.LicenseStringUtils;
import org.jboss.pnc.deliverablesanalyzer.utils.ManifestUtils;
import org.jboss.pnc.deliverablesanalyzer.utils.MavenUtils;
import org.jboss.pnc.deliverablesanalyzer.utils.SpdxLicenseUtils;
import org.jboss.pnc.deliverablesanalyzer.model.finder.BundleLicense;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.jboss.pnc.deliverablesanalyzer.utils.SpdxLicenseUtils.NOASSERTION;

@ApplicationScoped
public class LicenseService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LicenseService.class);

    /**
     * Aggregates licenses from a Main Jar by looking at its children (POMs, Manifests, License files).
     */
    public List<LicenseInfo> extractLicensesFromJar(FileObject jar, List<FileObject> children, String rootPath) {
        List<LicenseInfo> licenses = children.stream()
                .filter(this::isRelevantFile)
                .map(child -> findLicensesInChild(jar, child, rootPath))
                .flatMap(Collection::stream)
                .toList();

        // Second pass: match relative URLs (e.g. "META-INF/LICENSE")
        licenses.stream()
                .filter(l -> NOASSERTION.equals(l.getSpdxLicenseId()))
                .forEach(l -> handleRelativeURL(jar, l, rootPath));

        // If there are any licenses still unmatched, print them
        // Ignore unmatched files that were already checked in the last step
        if (LOGGER.isWarnEnabled()) {
            licenses.stream()
                    .filter(l -> NOASSERTION.equals(l.getSpdxLicenseId()))
                    .forEach(l -> checkMissingMapping(jar, l, rootPath));
        }

        return licenses;
    }

    private boolean isRelevantFile(FileObject child) {
        return MavenUtils.isPomXml(child) || ManifestUtils.isManifestMfFileName(child)
                || SpdxLicenseUtils.isLicenseFile(child);
    }

    private List<LicenseInfo> findLicensesInChild(FileObject jar, FileObject child, String rootPath) {
        try {
            if (MavenUtils.isPomXml(child)) {
                return getPomLicenses(child, rootPath);
            } else if (ManifestUtils.isManifestMfFileName(child)) {
                return getBundleLicenses(child);
            } else if (SpdxLicenseUtils.isLicenseFile(child)) {
                return getTextFileLicenses(jar, child);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to extract license from child {}", child.getName(), e);
        }
        return Collections.emptyList();
    }

    public List<LicenseInfo> getPomLicenses(FileObject pom, String rootPath) throws IOException {
        List<LicenseInfo> licenseInfos = extractLicensesFromPom(pom);
        if (licenseInfos.isEmpty()) {
            return Collections.emptyList();
        }

        String pomOrJarFile = AnalyzerUtils.normalizePath(pom, rootPath);
        if (LOGGER.isDebugEnabled()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Found {} SPDX licenses for {}: {}",
                        licenseInfos.size(),
                        pomOrJarFile,
                        String.join(
                                ", ",
                                licenseInfos.stream()
                                        .map(LicenseInfo::getSpdxLicenseId)
                                        .collect(Collectors.toUnmodifiableSet())));
            }
        }

        return licenseInfos;
    }

    private List<LicenseInfo> extractLicensesFromPom(FileObject pom) throws IOException {
        try {
            MavenProject mavenProject = MavenUtils.getMavenProject(pom);
            return mavenProject.getLicenses()
                    .stream()
                    .map(
                            l -> new LicenseInfo(
                                    l.getComments(),
                                    l.getDistribution(),
                                    l.getName(),
                                    l.getUrl(),
                                    SpdxLicenseUtils.getSPDXLicenseId(l.getName(), l.getUrl()),
                                    AnalyzerUtils.relativeLicensePath(pom)))
                    .toList();
        } catch (XmlPullParserException | InterpolationException e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Unable to read licenses from POM {}: {}", pom, AnalyzerUtils.getAllErrorMessages(e));
            }

            throw new IOException(e);
        }
    }

    private List<LicenseInfo> getBundleLicenses(FileObject manifest) throws IOException {
        List<BundleLicense> bundleLicenses = ManifestUtils.getBundleLicenseFromManifest(manifest);
        return bundleLicenses.stream().map(b -> {
            String name = LicenseStringUtils.getFirstNonBlankString(b.getLicenseIdentifier(), b.getDescription());
            String url = b.getLink();
            String spdxLicenseId = SpdxLicenseUtils.getSPDXLicenseId(name, url);
            String sourceUrl = AnalyzerUtils.relativeLicensePath(manifest);
            return new LicenseInfo(null, null, name, url, spdxLicenseId, sourceUrl);
        }).toList();
    }

    private List<LicenseInfo> getTextFileLicenses(FileObject jar, FileObject licenseFile) throws IOException {
        String licenseId = SpdxLicenseUtils.getMatchingLicense(licenseFile);
        String relativeName = jar.getName().getRelativeName(licenseFile.getName());
        String spdxLicenseId = !NOASSERTION.equals(licenseId) ? licenseId
                : SpdxLicenseUtils.getSPDXLicenseId(relativeName, null);
        String url = SpdxLicenseUtils.findFirstSeeAlsoUrl(spdxLicenseId).orElse(null);
        String sourceUrl = AnalyzerUtils.relativeLicensePath(licenseFile);
        return List.of(new LicenseInfo(null, null, relativeName, url, spdxLicenseId, sourceUrl));
    }

    private void handleRelativeURL(FileObject jar, LicenseInfo licenseInfo, String rootPath) {
        String url = licenseInfo.getUrl();
        if (LicenseStringUtils.isUrl(url) || url == null) {
            return; // Not a relative file path
        }
        String name = (licenseInfo.getName() != null) ? licenseInfo.getName() : url;

        try {
            FileObject licenseFile = jar.resolveFile(name);
            if (!SpdxLicenseUtils.isLicenseFile(licenseFile) || licenseFile.isFolder() || !licenseFile.isReadable()) {
                LOGGER.warn(
                        "License file {} in JAR {} is missing or unreadable",
                        name,
                        AnalyzerUtils.normalizePath(jar, rootPath));
                return;
            }

            List<LicenseInfo> licenseInfos = getTextFileLicenses(jar, licenseFile);
            if (licenseInfos.isEmpty()) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to add licenses from file {} located in JAR {}",
                            AnalyzerUtils.normalizePath(licenseFile, rootPath),
                            AnalyzerUtils.normalizePath(jar, rootPath));
                }

                return;
            }

            LicenseInfo resultLicense = licenseInfos.get(0);
            licenseInfo.setSpdxLicenseId(resultLicense.getSpdxLicenseId());
        } catch (IOException e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error resolving relative license {} in {}",
                        name,
                        AnalyzerUtils.normalizePath(jar, rootPath),
                        e);
            }
        }
    }

    private void checkMissingMapping(FileObject jar, LicenseInfo licenseInfo, String rootPath) {
        String name = licenseInfo.getName();
        String url = licenseInfo.getUrl();

        // Skip if no info is present, or if the info points to a license file (e.g. LICENSE.txt)
        if ((name == null && url == null) || SpdxLicenseUtils.isLicenseFileName(name)
                || SpdxLicenseUtils.isLicenseFileName(url)) {
            return;
        }

        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "Missing SPDX license mapping for name: {}, URL: {}, filename: {}",
                    name,
                    url,
                    AnalyzerUtils.normalizePath(jar, rootPath));
        }
    }
}
