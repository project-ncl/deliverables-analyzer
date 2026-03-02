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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.jboss.pnc.deliverablesanalyzer.model.finder.AnalyzerObjectMapper;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static org.spdx.library.LicenseInfoFactory.NOASSERTION_LICENSE_NAME;
import static org.spdx.library.LicenseInfoFactory.NONE_LICENSE_NAME;

public final class SpdxLicenseUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpdxLicenseUtils.class);

    // Constants
    public static final String NOASSERTION = NOASSERTION_LICENSE_NAME;
    public static final String NONE = NONE_LICENSE_NAME;
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

    private SpdxLicenseUtils() {
        // Prevent instantiation
    }

    // --- DATA HOLDER ---

    private static final class SpdxDataHolder {
        static final Map<String, ListedLicense> IDS_MAP;
        static final Map<String, ListedLicense> NAMES_MAP;
        static final List<String> IDS_LIST;
        static final List<String> NAMES_LIST;
        static final Map<String, String> DEPRECATED_MAP;
        static final Map<String, List<String>> MAPPINGS_MAP;

        static {
            try {
                // Fetch and process SPDX Licenses from network/library
                SpdxDataResult spdxData = loadSpdxData();
                IDS_MAP = Map.copyOf(spdxData.idsMap);
                NAMES_MAP = Map.copyOf(spdxData.namesMap);
                IDS_LIST = spdxData.sortedIds; // Already unmodifiable
                NAMES_LIST = List.copyOf(spdxData.sortedNames);

                // Load Local JSON configurations
                DEPRECATED_MAP = loadDeprecatedLicenses();
                MAPPINGS_MAP = loadLicenseMappings();
            } catch (Exception e) {
                throw new ExceptionInInitializerError("Failed to initialize SPDX Data: " + e.getMessage());
            }
        }

        // --- Initialization Helpers ---

        private record SpdxDataResult(
                Map<String, ListedLicense> idsMap,
                Map<String, ListedLicense> namesMap,
                List<String> sortedIds,
                List<String> sortedNames) {
        }

        private static SpdxDataResult loadSpdxData() {
            List<String> rawIds = ResilienceUtils.retry(LicenseInfoFactory::getSpdxListedLicenseIds);

            Map<String, ListedLicense> idsMap = new LinkedHashMap<>(EXPECTED_NUM_SPDX_LICENSES);
            Map<String, ListedLicense> namesMap = new LinkedHashMap<>(EXPECTED_NUM_SPDX_LICENSES);
            List<String> namesList = new ArrayList<>(EXPECTED_NUM_SPDX_LICENSES);

            // Sort by length desc, then natural to ensure longest match first
            List<String> sortedIds = rawIds.stream()
                    .sorted(comparing(String::length).reversed().thenComparing(naturalOrder()))
                    .toList();

            for (String id : sortedIds) {
                ResilienceUtils.retry(() -> {
                    try {
                        ListedLicense license = LicenseInfoFactory.getListedLicenseById(id);
                        idsMap.put(license.getId(), license);
                        license.getName().ifPresent(name -> {
                            namesMap.put(name, license);
                            namesList.add(name);
                        });
                        return license;
                    } catch (InvalidSPDXAnalysisException e) {
                        throw new IllegalArgumentException(e);
                    }
                });
            }

            namesList.sort(comparing(String::length).reversed().thenComparing(naturalOrder()));
            return new SpdxDataResult(idsMap, namesMap, sortedIds, namesList);
        }

        private static Map<String, String> loadDeprecatedLicenses() throws IOException {
            try (InputStream in = SpdxLicenseUtils.class.getClassLoader()
                    .getResourceAsStream(LICENSE_DEPRECATED_FILENAME)) {
                if (in == null) {
                    return Collections.emptyMap();
                }

                Map<String, String> map = new AnalyzerObjectMapper()
                        .readValue(in, new TypeReference<LinkedHashMap<String, String>>() {
                        });

                map.forEach((k, v) -> {
                    validateLicenseStringSyntax(k);
                    validateLicenseStringSyntax(v);
                });
                return Map.copyOf(map);
            }
        }

        private static Map<String, List<String>> loadLicenseMappings() throws IOException {
            try (InputStream in = SpdxLicenseUtils.class.getClassLoader()
                    .getResourceAsStream(LICENSE_MAPPING_FILENAME)) {
                if (in == null) {
                    return Collections.emptyMap();
                }

                ObjectMapper mapper = new AnalyzerObjectMapper();
                Map<String, List<String>> map = mapper
                        .readValue(in, new TypeReference<LinkedHashMap<String, List<String>>>() {
                        });

                validateMappings(map);
                return Map.copyOf(map);
            }
        }

        private static void validateMappings(Map<String, List<String>> map) {
            for (String key : map.keySet()) {
                if (IDSTRING_PATTERN.matcher(key).matches()) {
                    validateSpdxIdExists(key);
                } else {
                    validateLicenseStringSyntax(key);
                }
            }
        }
    }

    // --- QUERY API (Getters & Lookups) ---

    public static Map<String, List<String>> getSpdxLicenseMapping() {
        return SpdxDataHolder.MAPPINGS_MAP;
    }

    public static int getNumberOfSPDXLicenses() {
        return SpdxDataHolder.IDS_MAP.size();
    }

    public static String getSPDXLicenseListVersion() {
        return LicenseInfoFactory.getLicenseListVersion();
    }

    public static boolean isKnownLicenseId(String licenseId) {
        return SpdxDataHolder.IDS_LIST.contains(licenseId);
    }

    public static boolean isUnknownLicenseId(String licenseId) {
        return NOASSERTION.equals(licenseId) || NONE.equals(licenseId);
    }

    /**
     * Resolves deprecated license IDs to their current valid ID.
     */
    private static String getCurrentLicenseId(String licenseId) {
        return SpdxDataHolder.DEPRECATED_MAP.getOrDefault(licenseId, licenseId);
    }

    public static String getSPDXLicenseId(String name, String url) {
        return getSPDXLicenseId(SpdxDataHolder.MAPPINGS_MAP, name, url);
    }

    private static String getSPDXLicenseId(Map<String, List<String>> mapping, String name, String url) {
        return findLicenseMapping(mapping, url).or(() -> findMatchingLicense(name, url))
                .or(() -> findLicenseMapping(mapping, name))
                .orElse(NOASSERTION);
    }

    // --- MATCHING LOGIC (Heuristic & String) ---

    private static Optional<String> findLicenseMapping(Map<String, List<String>> mapping, String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        // Check exact custom mappings
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

    private static boolean matchesCandidate(String query, String candidate) {
        if (LicenseStringUtils.isUrl(query, candidate)) {
            return LicenseStringUtils.normalizeLicenseUrl(query)
                    .equals(LicenseStringUtils.normalizeLicenseUrl(candidate));
        }
        return normalizeSpace(query).equalsIgnoreCase(normalizeSpace(candidate));
    }

    private static String normalizeSpace(String s) {
        if (s == null) {
            return null;
        }
        return s.strip().replaceAll("\\s+", " ");
    }

    private static Optional<String> findMatchingLicense(String licenseName, String licenseUrl) {
        return findMatchingLicenseId(licenseName).or(() -> findMatchingLicenseName(licenseName, licenseUrl))
                .or(() -> findMatchingLicenseSeeAlso(licenseUrl));
    }

    private static Optional<String> findMatchingLicenseId(String licenseId) {
        if (licenseId == null || licenseId.isBlank()) {
            return Optional.empty();
        }
        ListedLicense license = SpdxDataHolder.IDS_MAP.get(licenseId);
        return Optional.ofNullable(license).map(l -> getCurrentLicenseId(l.getId()));
    }

    public static Optional<String> findMatchingLicenseName(String licenseName, String licenseUrl) {
        // 1. Search in IDs (Partial Match)
        for (String id : SpdxDataHolder.IDS_LIST) {
            String normalizedId = id.replace("-only", "");
            if (LicenseStringUtils.containsWordsInSameOrder(licenseName, normalizedId)
                    || LicenseStringUtils.containsWordsInSameOrder(licenseUrl, id)) {
                return Optional.of(getCurrentLicenseId(id));
            }
        }

        // 2. Search in Names (Exact & Partial Match)
        for (String name : SpdxDataHolder.NAMES_LIST) {
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

    private static Optional<String> getLicenseIdFromName(String name) {
        ListedLicense license = SpdxDataHolder.NAMES_MAP.get(name);
        return license != null ? Optional.of(getCurrentLicenseId(license.getId())) : Optional.empty();
    }

    private static Optional<String> findMatchingLicenseSeeAlso(String licenseUrl) {
        if (!LicenseStringUtils.isUrl(licenseUrl)) {
            return Optional.empty();
        }

        String normalizedUrl = LicenseStringUtils.normalizeLicenseUrl(licenseUrl);

        return SpdxDataHolder.IDS_MAP.values()
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

    public static Optional<String> findFirstSeeAlsoUrl(String licenseId) {
        if (isUnknownLicenseId(licenseId)) {
            return Optional.empty();
        }
        ListedLicense license = SpdxDataHolder.IDS_MAP.get(licenseId);
        if (license == null) {
            return Optional.empty();
        }

        return license.getSeeAlsos().stream().filter(LicenseStringUtils::isUrl).findFirst();
    }

    // --- FILE CONTENT MATCHING ---

    public static String getMatchingLicense(FileObject licenseFileObject) {
        return findMatchingLicense(licenseFileObject).orElse(NOASSERTION);
    }

    private static Optional<String> findMatchingLicense(FileObject licenseFileObject) {
        // 1. Check Filename
        Optional<String> byName = findSPDXIdentifierFromFileName(licenseFileObject);
        if (byName.isPresent()) {
            return byName;
        }

        // 2. Check Content
        try (FileContent fc = licenseFileObject.getContent(); InputStream in = fc.getInputStream()) {

            String licenseText = new String(in.readAllBytes(), UTF_8);

            // A. Check against priority list (Expensive full text comparison)
            Optional<String> priorityMatch = LICENSE_IDS_TEXT_LIST.stream()
                    .map(SpdxDataHolder.IDS_MAP::get)
                    .map(l -> matchTextToLicense(l, licenseText))
                    .flatMap(Optional::stream)
                    .findAny();

            if (priorityMatch.isPresent()) {
                return priorityMatch;
            }

            // B. Check for "SPDX-License-Identifier" tag or fallback full match
            return findMatchingSPDXLicenseIdentifier(licenseText).or(() -> findMatchingLicense(licenseText, null));

        } catch (IOException e) {
            LOGGER.warn("Failed to read license file content: {}", licenseFileObject.getName(), e);
            return Optional.empty();
        }
    }

    private static Optional<String> matchTextToLicense(ListedLicense license, String text) {
        if (license == null) {
            return Optional.empty();
        }
        try {
            if (!LicenseCompareHelper.isTextStandardLicense(license, text).isDifferenceFound()) {
                return Optional.of(getCurrentLicenseId(license.getId()));
            }
        } catch (SpdxCompareException | InvalidSPDXAnalysisException e) {
            LOGGER.trace("SPDX compare error", e);
        }
        return Optional.empty();
    }

    private static Optional<String> findSPDXIdentifierFromFileName(FileObject fileObject) {
        try {
            if (!fileObject.isFile()) {
                return Optional.empty();
            }
            String baseName = FilenameUtils.getBaseName(fileObject.getName().getPath());
            return isKnownLicenseId(baseName) ? Optional.of(baseName) : Optional.empty();
        } catch (FileSystemException e) {
            return Optional.empty();
        }
    }

    public static boolean isLicenseFile(FileObject fileObject) {
        try {
            if (!fileObject.isFile()) {
                return false;
            }
            return isLicenseFileName(fileObject.getName().getPath());
        } catch (FileSystemException e) {
            return false;
        }
    }

    public static boolean isLicenseFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }

        String extension = FilenameUtils.getExtension(fileName);
        if (!LicenseStringUtils.isTextExtension(extension)) {
            return false;
        }

        // 3. Extract Base Name
        String name = FilenameUtils.getName(fileName);
        String baseName = FilenameUtils.removeExtension(name);

        return baseName.toUpperCase().contains(LICENSE) || isKnownLicenseId(baseName);
    }

    /**
     * Finds the license from the SPDX-License-Identifier token.
     */
    private static Optional<String> findMatchingSPDXLicenseIdentifier(String licenseString) {
        Matcher matcher = SPDX_LICENSE_IDENTIFIER_PATTERN.matcher(licenseString);
        return matcher.find() ? Optional.of(getCurrentLicenseId(matcher.group(1))) : Optional.empty();
    }

    // --- Validation Methods ---

    private static void validateLicenseStringSyntax(String licenseString) {
        try {
            AnyLicenseInfo info = parseSPDXLicenseString(licenseString);
            if (info instanceof ListedLicense listed) {
                validateSpdxIdExists(listed.getId());
            }
        } catch (InvalidLicenseStringException | DefaultStoreNotInitializedException e) {
            throw new IllegalArgumentException("Could not parse license string: '" + licenseString + "'", e);
        }
    }

    private static AnyLicenseInfo parseSPDXLicenseString(String licenseString)
            throws InvalidLicenseStringException, DefaultStoreNotInitializedException {
        return LicenseInfoFactory.parseSPDXLicenseString(licenseString);
    }

    private static void validateSpdxIdExists(String licenseId) {
        if (!isUnknownLicenseId(licenseId) && !isKnownLicenseId(licenseId)) {
            throw new IllegalArgumentException(
                    "License identifier '" + licenseId + "' is not in list of SPDX licenses");
        }
    }
}
