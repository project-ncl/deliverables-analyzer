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
package org.jboss.pnc.deliverablesanalyzer.license;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.jboss.pnc.deliverablesanalyzer.config.BuildConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.core.DefaultStoreNotInitializedException;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.library.LicenseInfoFactory;
import org.spdx.library.model.v2.license.InvalidLicenseStringException;
import org.spdx.library.model.v3_0_1.expandedlicensing.ListedLicense;
import org.spdx.library.model.v3_0_1.simplelicensing.AnyLicenseInfo;
import org.spdx.utility.compare.LicenseCompareHelper;
import org.spdx.utility.compare.SpdxCompareException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.Startup;

@Startup
@ApplicationScoped
public class LicenseRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(LicenseRegistry.class);

    // Constants
    public static final String NOASSERTION = LicenseInfoFactory.NOASSERTION_LICENSE_NAME;
    public static final String NONE = LicenseInfoFactory.NONE_LICENSE_NAME;
    private static final String LICENSE = "LICENSE";

    private static final String LICENSE_MAPPING_FILENAME = "build-finder-license-mapping.json";
    private static final String LICENSE_DEPRECATED_FILENAME = "build-finder-license-deprecated.json";

    // Regex
    private static final Pattern IDSTRING_PATTERN = Pattern.compile("[a-zA-Z0-9-.]+");
    private static final Pattern SPDX_LICENSE_IDENTIFIER_PATTERN = Pattern
            .compile("SPDX-License-Identifier:\\s*(" + IDSTRING_PATTERN.pattern() + ")");

    // Defaults
    private static final int EXPECTED_NUM_SPDX_LICENSES = 1024;

    // Priority list for full text matching
    private static final List<String> LICENSE_IDS_TEXT_LIST = List
            .of("Apache-2.0", "BSD-3-Clause", "EPL-1.0", "BSD-2-Clause", "MIT", "xpp", "Plexus");

    // Pre-loaded license data structures
    private Map<String, ListedLicense> idsMap;
    private Map<String, ListedLicense> namesMap;
    private List<String> idsList;
    private List<String> namesList;
    private Map<String, String> deprecatedMap;
    private Map<String, List<String>> mappingsMap;

    @Inject
    BuildConfig buildConfig;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SpdxClient spdxClient;

    @PostConstruct
    void init() {
        if (buildConfig.disableSpdxInit()) {
            LOGGER.info("Skipping heavy SPDX License initialization via config (Test Mode).");
            this.idsList = Collections.emptyList();
            this.namesList = Collections.emptyList();
            this.idsMap = Collections.emptyMap();
            this.namesMap = Collections.emptyMap();
            this.deprecatedMap = Collections.emptyMap();
            this.mappingsMap = Collections.emptyMap();
            return;
        }

        LOGGER.info("Initializing SPDX License Manager...");
        try {
            loadSpdxData();
            this.deprecatedMap = loadDeprecatedLicenses();
            this.mappingsMap = loadLicenseMappings();
            LOGGER.info("Successfully loaded {} SPDX licenses.", idsMap.size());
        } catch (Exception e) {
            // Throwing a RuntimeException here fails the Quarkus startup safely
            throw new RuntimeException("Failed to initialize SPDX License Data", e);
        }
    }

    // --- INITIALIZATION ---

    private void loadSpdxData() {
        List<String> rawIds = spdxClient.getSpdxListedLicenseIds();

        Map<String, ListedLicense> tempIdsMap = new LinkedHashMap<>(EXPECTED_NUM_SPDX_LICENSES);
        Map<String, ListedLicense> tempNamesMap = new LinkedHashMap<>(EXPECTED_NUM_SPDX_LICENSES);
        List<String> tempNamesList = new ArrayList<>(EXPECTED_NUM_SPDX_LICENSES);

        // Sort by length desc, then natural to ensure longest match first
        this.idsList = rawIds.stream()
                .sorted(Comparator.comparing(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();

        for (String id : this.idsList) {
            try {
                ListedLicense license = spdxClient.getListedLicenseById(id);
                tempIdsMap.put(license.getId(), license);
                license.getName().ifPresent(name -> {
                    tempNamesMap.put(name, license);
                    tempNamesList.add(name);
                });
            } catch (InvalidSPDXAnalysisException e) {
                throw new IllegalArgumentException(e);
            }
        }

        tempNamesList.sort(Comparator.comparing(String::length).reversed().thenComparing(Comparator.naturalOrder()));

        // Ensure thread-safe read-only maps/lists for concurrent execution
        this.namesList = List.copyOf(tempNamesList);
        this.idsMap = Map.copyOf(tempIdsMap);
        this.namesMap = Map.copyOf(tempNamesMap);
    }

    private Map<String, String> loadDeprecatedLicenses() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(LICENSE_DEPRECATED_FILENAME)) {
            if (in == null)
                return Collections.emptyMap();

            Map<String, String> map = objectMapper.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {
            });
            map.forEach((k, v) -> {
                validateLicenseStringSyntax(k);
                validateLicenseStringSyntax(v);
            });
            return Map.copyOf(map);
        }
    }

    private Map<String, List<String>> loadLicenseMappings() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(LICENSE_MAPPING_FILENAME)) {
            if (in == null)
                return Collections.emptyMap();

            Map<String, List<String>> map = objectMapper
                    .readValue(in, new TypeReference<LinkedHashMap<String, List<String>>>() {
                    });
            validateMappings(map);
            return Map.copyOf(map);
        }
    }

    private void validateMappings(Map<String, List<String>> map) {
        for (String key : map.keySet()) {
            if (IDSTRING_PATTERN.matcher(key).matches()) {
                validateSpdxIdExists(key);
            } else {
                validateLicenseStringSyntax(key);
            }
        }
    }

    // --- QUERY API ---

    public boolean isKnownLicenseId(String licenseId) {
        return idsList.contains(licenseId);
    }

    public boolean isUnknownLicenseId(String licenseId) {
        return NOASSERTION.equals(licenseId) || NONE.equals(licenseId);
    }

    private String getCurrentLicenseId(String licenseId) {
        return deprecatedMap.getOrDefault(licenseId, licenseId);
    }

    public String getSPDXLicenseId(String name, String url) {
        return findLicenseMapping(mappingsMap, url).or(() -> findMatchingLicense(name, url))
                .or(() -> findLicenseMapping(mappingsMap, name))
                .orElseGet(() -> {
                    if (name != null || url != null) {
                        LOGGER.warn(
                                "[UNMAPPED_LICENSE] Unrecognized license strings found. Name: '{}', URL: '{}'. Defaulting to {}",
                                name,
                                url,
                                NOASSERTION);
                    }
                    return NOASSERTION;
                });
    }

    // --- MATCHING LOGIC ---

    private Optional<String> findLicenseMapping(Map<String, List<String>> mapping, String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        for (var entry : mapping.entrySet()) {
            String licenseId = entry.getKey();
            for (String candidate : entry.getValue()) {
                if (matchesCandidate(query, candidate)) {
                    return Optional.of(getCurrentLicenseId(licenseId));
                }
            }
        }
        return Optional.empty();
    }

    private boolean matchesCandidate(String query, String candidate) {
        if (LicenseStringUtils.isUrl(query, candidate)) {
            return LicenseStringUtils.normalizeLicenseUrl(query)
                    .equals(LicenseStringUtils.normalizeLicenseUrl(candidate));
        }
        return normalizeSpace(query).equalsIgnoreCase(normalizeSpace(candidate));
    }

    private String normalizeSpace(String s) {
        if (s == null)
            return null;
        return s.strip().replaceAll("\\s+", " ");
    }

    private Optional<String> findMatchingLicense(String licenseName, String licenseUrl) {
        return findMatchingLicenseId(licenseName).or(() -> findMatchingLicenseName(licenseName, licenseUrl))
                .or(() -> findMatchingLicenseSeeAlso(licenseUrl));
    }

    private Optional<String> findMatchingLicenseId(String licenseId) {
        if (licenseId == null || licenseId.isBlank())
            return Optional.empty();

        ListedLicense license = idsMap.get(licenseId);
        return Optional.ofNullable(license).map(l -> getCurrentLicenseId(l.getId()));
    }

    public Optional<String> findMatchingLicenseName(String licenseName, String licenseUrl) {
        // Search in IDs (Partial Match)
        for (String id : idsList) {
            String normalizedId = id.replace("-only", "");
            if (LicenseStringUtils.containsWordsInSameOrder(licenseName, normalizedId)
                    || LicenseStringUtils.containsWordsInSameOrder(licenseUrl, id)) {
                return Optional.of(getCurrentLicenseId(id));
            }
        }

        // Search in Names (Exact & Partial Match)
        for (String name : namesList) {
            if (name.equalsIgnoreCase(licenseName)) {
                return getLicenseIdFromName(name);
            }

            String normalizedName = name.replace(" only", "");
            if (LicenseStringUtils.containsWordsInSameOrder(licenseName, normalizedName)
                    || LicenseStringUtils.containsWordsInSameOrder(licenseUrl, name)) {
                return getLicenseIdFromName(name);
            }
        }
        return Optional.empty();
    }

    private Optional<String> getLicenseIdFromName(String name) {
        ListedLicense license = namesMap.get(name);
        return license != null ? Optional.of(getCurrentLicenseId(license.getId())) : Optional.empty();
    }

    private Optional<String> findMatchingLicenseSeeAlso(String licenseUrl) {
        if (!LicenseStringUtils.isUrl(licenseUrl))
            return Optional.empty();

        String normalizedUrl = LicenseStringUtils.normalizeLicenseUrl(licenseUrl);

        return idsMap.values()
                .stream()
                .filter(
                        l -> l.getSeeAlsos()
                                .stream()
                                .filter(LicenseStringUtils::isUrl)
                                .map(LicenseStringUtils::normalizeLicenseUrl)
                                .anyMatch(normalizedUrl::equals))
                .map(l -> getCurrentLicenseId(l.getId()))
                .findFirst();
    }

    public Optional<String> findFirstSeeAlsoUrl(String licenseId) {
        if (isUnknownLicenseId(licenseId))
            return Optional.empty();

        ListedLicense license = idsMap.get(licenseId);
        if (license == null)
            return Optional.empty();

        return license.getSeeAlsos().stream().filter(LicenseStringUtils::isUrl).findFirst();
    }

    // --- FILE CONTENT MATCHING ---

    public String getMatchingLicense(FileObject licenseFileObject) {
        return findMatchingLicense(licenseFileObject).orElseGet(() -> {
            LOGGER.warn(
                    "[UNMAPPED_LICENSE] Unrecognized license file content found in: {}. Defaulting to {}",
                    licenseFileObject.getName().getFriendlyURI(),
                    NOASSERTION);
            return NOASSERTION;
        });
    }

    private Optional<String> findMatchingLicense(FileObject licenseFileObject) {
        // Check Filename
        Optional<String> byName = findSPDXIdentifierFromFileName(licenseFileObject);
        if (byName.isPresent())
            return byName;

        // Check Content
        try (FileContent fc = licenseFileObject.getContent(); InputStream in = fc.getInputStream()) {
            String licenseText = new String(in.readAllBytes(), UTF_8);

            // Check against priority list (Expensive full text comparison)
            Optional<String> priorityMatch = LICENSE_IDS_TEXT_LIST.stream()
                    .map(idsMap::get)
                    .map(l -> matchTextToLicense(l, licenseText))
                    .flatMap(Optional::stream)
                    .findAny();

            if (priorityMatch.isPresent()) {
                return priorityMatch;
            }

            // Check for "SPDX-License-Identifier" tag or fallback full match
            String cleanedText = LicenseStringUtils.licenseFileToText(licenseText);
            return findMatchingSPDXLicenseIdentifier(cleanedText).or(() -> findMatchingLicense(cleanedText, null));

        } catch (IOException e) {
            LOGGER.warn("Failed to read license file content: {}", licenseFileObject.getName(), e);
            return Optional.empty();
        }
    }

    private Optional<String> matchTextToLicense(ListedLicense license, String text) {
        if (license == null || text == null)
            return Optional.empty();

        try {
            if (!LicenseCompareHelper.isTextStandardLicense(license, text).isDifferenceFound()) {
                return Optional.of(getCurrentLicenseId(license.getId()));
            }
        } catch (SpdxCompareException | InvalidSPDXAnalysisException e) {
            LOGGER.trace("SPDX compare error", e);
        }
        return Optional.empty();
    }

    private Optional<String> findSPDXIdentifierFromFileName(FileObject fileObject) {
        try {
            if (!fileObject.isFile())
                return Optional.empty();
            String baseName = FilenameUtils.getBaseName(fileObject.getName().getPath());
            return isKnownLicenseId(baseName) ? Optional.of(baseName) : Optional.empty();
        } catch (FileSystemException e) {
            return Optional.empty();
        }
    }

    public boolean isLicenseFile(FileObject fileObject) {
        try {
            if (!fileObject.isFile())
                return false;
            return isLicenseFileName(fileObject.getName().getPath());
        } catch (FileSystemException e) {
            return false;
        }
    }

    public boolean isLicenseFileName(String fileName) {
        if (fileName == null || fileName.isBlank())
            return false;

        String extension = FilenameUtils.getExtension(fileName);
        if (!LicenseStringUtils.isTextExtension(extension)) {
            return false;
        }

        String name = FilenameUtils.getName(fileName);
        String baseName = FilenameUtils.removeExtension(name);

        return baseName.toUpperCase().contains(LICENSE) || isKnownLicenseId(baseName);
    }

    private Optional<String> findMatchingSPDXLicenseIdentifier(String licenseString) {
        Matcher matcher = SPDX_LICENSE_IDENTIFIER_PATTERN.matcher(licenseString);
        return matcher.find() ? Optional.of(getCurrentLicenseId(matcher.group(1))) : Optional.empty();
    }

    // --- VALIDATION METHODS ---

    private void validateLicenseStringSyntax(String licenseString) {
        try {
            AnyLicenseInfo info = parseSPDXLicenseString(licenseString);
            if (info instanceof ListedLicense listed) {
                validateSpdxIdExists(listed.getId());
            }
        } catch (InvalidLicenseStringException | DefaultStoreNotInitializedException e) {
            throw new IllegalArgumentException("Could not parse license string: '" + licenseString + "'", e);
        }
    }

    private AnyLicenseInfo parseSPDXLicenseString(String licenseString)
            throws InvalidLicenseStringException, DefaultStoreNotInitializedException {
        return LicenseInfoFactory.parseSPDXLicenseString(licenseString);
    }

    private void validateSpdxIdExists(String licenseId) {
        if (!isUnknownLicenseId(licenseId) && !isKnownLicenseId(licenseId)) {
            throw new IllegalArgumentException(
                    "License identifier '" + licenseId + "' is not in list of SPDX licenses");
        }
    }
}
