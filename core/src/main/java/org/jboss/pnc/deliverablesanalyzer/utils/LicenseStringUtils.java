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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.SPACE;

public final class LicenseStringUtils {

    private static final Pattern PUNCT_PATTERN = Pattern.compile("\\p{Punct}");
    private static final Pattern NAME_VERSION_PATTERN = Pattern
            .compile("(?<name>[A-Z-a-z])[Vv]?(?<major>[1-9]+)(\\.(?<minor>\\d+))?");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SINGLE_DIGIT_PATTERN = Pattern.compile("(?<b>[^\\d.])(?<major>[1-9])(?<a>[^\\d.])");
    private static final Pattern TWO_DIGIT_PATTERN = Pattern.compile("(\\d)(\\d)");
    private static final Pattern LETTER_DIGIT_PATTERN = Pattern.compile("([A-Za-z])(\\d)");
    private static final List<String> TEXT_EXTENSIONS = List.of(".html", ".md", ".php", ".txt");

    private static final int LINE_LIMIT = 5;

    private static final String URL_MARKER = ":/";
    private static final String UNINTERPOLATED_PROPERTY_MARKER = "${";

    private LicenseStringUtils() {
        // Prevent instantiation
    }

    // --- URL Logic ---

    static String normalizeLicenseUrl(String licenseUrl) {
        // Normalize URI
        URI uri = URI.create(licenseUrl).normalize();

        // Process Host
        String host = uri.getHost();
        if (host == null) {
            host = "";
        } else {
            host = host.replace("www.", "").replace("creativecommons", "cc").replace('.', '-');
        }

        // Process Path
        String path = uri.getPath();
        if (path == null) {
            path = "";
        } else {
            // Regex Replacements
            path = TWO_DIGIT_PATTERN.matcher(path).replaceAll("$1.$2");
            path = LETTER_DIGIT_PATTERN.matcher(path).replaceAll("$1-$2");
            path = path.replace("cc-0", "cc0");

            // Remove Extensions
            for (String extension : TEXT_EXTENSIONS) {
                if (path.endsWith(extension)) {
                    path = path.substring(0, path.length() - extension.length());
                }
            }

            // Remove trailing slash
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }

        return host + path;
    }

    public static boolean isUrl(String... strings) {
        if (strings == null) {
            return false;
        }

        for (String s : strings) {
            if (!isValidUrl(s)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isValidUrl(String s) {
        return s != null && s.contains(URL_MARKER) && s.chars().noneMatch(Character::isWhitespace)
                && !s.contains(UNINTERPOLATED_PROPERTY_MARKER);
    }

    // --- Text / Extension Logic ---

    public static boolean isTextExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return true;
        }
        String ext = extension.startsWith(".") ? extension : "." + extension;
        return TEXT_EXTENSIONS.contains(ext);
    }

    public static String getFirstNonBlankString(String... strings) {
        return findFirstNonBlankString(strings).orElse(null);
    }

    private static Optional<String> findFirstNonBlankString(String... strings) {
        for (String string : strings) {
            if (string != null && !string.isBlank()) {
                return Optional.of(string);
            }
        }
        return Optional.empty();
    }

    public static String licenseFileToText(String text) {
        return text.lines().limit(LINE_LIMIT).map(String::trim).collect(Collectors.joining(SPACE));
    }

    // --- Matching / Tokenization Logic ---

    public static boolean containsWordsInSameOrder(String licenseStringCandidate, String licenseString) {
        if (licenseStringCandidate == null || licenseString == null) {
            return false;
        }

        List<String> candidates = tokenizeLicenseString(licenseStringCandidate);
        List<String> targets = tokenizeLicenseString(licenseString);

        if (targets.isEmpty()) {
            return true;
        }

        int targetIndex = 0;
        for (String candidate : candidates) {
            if (candidate.equals(targets.get(targetIndex))) {
                targetIndex++;

                if (targetIndex == targets.size()) {
                    return true;
                }
            }
        }

        return false;
    }

    private static List<String> tokenizeLicenseString(String licenseString) {
        String newLicenseString = licenseString;

        if (LicenseStringUtils.isUrl(newLicenseString)) {
            newLicenseString = LicenseStringUtils.normalizeLicenseUrl(newLicenseString);
            newLicenseString = newLicenseString.replace('/', '-');
        }

        newLicenseString = SINGLE_DIGIT_PATTERN.matcher(newLicenseString).replaceAll("${b}${major}.0${a}");
        newLicenseString = newLicenseString.replace('.', '_').replace('-', ' ');
        newLicenseString = PUNCT_PATTERN.matcher(newLicenseString).replaceAll("");
        newLicenseString = NAME_VERSION_PATTERN.matcher(newLicenseString).replaceAll("${name} ${major} ${minor}");
        newLicenseString = newLicenseString.toLowerCase(Locale.ROOT);

        List<String> list = new ArrayList<>(Arrays.asList(WHITESPACE_PATTERN.split(newLicenseString)));
        list.remove("v");

        return Collections.unmodifiableList(list);
    }
}
