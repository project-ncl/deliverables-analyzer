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

import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.jboss.pnc.deliverablesanalyzer.license.LicenseStringUtils;
import org.jboss.pnc.deliverablesanalyzer.model.finder.BundleLicense;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

public final class ManifestUtils {

    private static final Pattern MANIFEST_MF_PATTERN = Pattern.compile("^.*META-INF/MANIFEST.MF$");
    private static final String BUNDLE_LICENSE = "Bundle-License";

    private static final String EXTERNAL = "<<EXTERNAL>>";
    private static final String LINK = "link";
    private static final String DESCRIPTION = "description";

    private static final Pattern LICENSE_LIST_PATTERN = Pattern.compile("\\s*,\\s*(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
    private static final Pattern LICENSE_PATTERN = Pattern.compile("\\s*;\\s*");
    private static final Pattern LICENSE_ATTRIBUTE_PATTERN = Pattern.compile("\\s*=\\s*");

    private ManifestUtils() {
        // Prevent instantiation
    }

    public static boolean isManifestMfFileName(FileObject fileObject) {
        return MANIFEST_MF_PATTERN.matcher(fileObject.getName().getPath()).matches();
    }

    public static List<BundleLicense> getBundleLicenseFromManifest(FileObject manifestFileObject) throws IOException {
        try (FileContent fc = manifestFileObject.getContent(); InputStream in = fc.getInputStream()) {
            Manifest manifest = new Manifest(in);
            Attributes mainAttributes = manifest.getMainAttributes();
            String bundleLicense = mainAttributes.getValue(BUNDLE_LICENSE);
            return getBundleLicenseFromManifest(bundleLicense);
        }
    }

    private static List<BundleLicense> getBundleLicenseFromManifest(String bundleLicense) throws IOException {
        if (bundleLicense == null || bundleLicense.isBlank() || EXTERNAL.equals(bundleLicense)) {
            return List.of();
        }

        String[] split = LICENSE_LIST_PATTERN.split(bundleLicense);
        List<BundleLicense> list = new ArrayList<>(split.length);

        for (String string : split) {
            list.add(parseLicenseString(string));
        }

        return Collections.unmodifiableList(list);
    }

    private static BundleLicense parseLicenseString(String value) throws IOException {
        BundleLicense bundleLicense = new BundleLicense();
        // The format is usually "Identifier; key=value; key=value"
        // We limit split to 2 to separate Identifier from the Attributes block
        String[] tokens = LICENSE_PATTERN.split(value, 2);

        String identifier = unwrapQuotes(tokens[0]);

        if (LicenseStringUtils.isUrl(identifier)) {
            bundleLicense.setLink(identifier);
        } else {
            bundleLicense.setLicenseIdentifier(identifier);
        }

        if (tokens.length > 1) {
            parseAttributes(bundleLicense, tokens[1]);
        }

        return bundleLicense;
    }

    private static void parseAttributes(BundleLicense bundleLicense, String attributesBlock) throws IOException {
        // The attributes block itself might be quoted, e.g. "link=foo;desc=bar"
        String unquotedBlock = unwrapQuotes(attributesBlock);
        String[] attributes = LICENSE_PATTERN.split(unquotedBlock);

        for (String attribute : attributes) {
            String[] kv = LICENSE_ATTRIBUTE_PATTERN.split(attribute, 2);

            if (kv.length != 2) {
                throw new IOException("Expected key=value pair, but got " + attribute);
            }

            String key = unwrapQuotes(kv[0]);
            String value = unwrapQuotes(kv[1]);

            switch (key) {
                case LINK -> {
                    if (!LicenseStringUtils.isUrl(value)) {
                        throw new IOException("Expected URL, but got " + value);
                    }
                    bundleLicense.setLink(value);
                }
                case DESCRIPTION -> bundleLicense.setDescription(value);
                default -> throw new IOException("Unknown key " + key);
            }
        }
    }

    private static String unwrapQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
