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

import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;

public final class AnalyzerUtils {
    private static final String BANG_SLASH = "!/";

    private AnalyzerUtils() {
        // Prevent instantiation
    }

    // --- VFS Path Helpers ---

    public static String normalizePath(FileObject fileObject, String root) {
        String friendlyURI = fileObject.getName().getFriendlyURI();
        return friendlyURI.substring(friendlyURI.indexOf(root) + root.length());
    }

    public static String licensePath(FileObject fileObject, String root) {
        String normalizedPath = normalizePath(fileObject, root);
        if (normalizedPath.endsWith(BANG_SLASH)) {
            return normalizedPath.substring(0, normalizedPath.length() - BANG_SLASH.length());
        }
        return normalizedPath;
    }

    public static String relativeLicensePath(FileObject fileObject) {
        String friendlyURI = fileObject.getName().getFriendlyURI();
        int index = friendlyURI.lastIndexOf("!/");
        if (index == -1) {
            return fileObject.getName().getBaseName();
        }
        return friendlyURI.substring(index + 2);
    }

    public static String calculateRootPath(FileObject fileObject) {
        String uri = fileObject.getName().getFriendlyURI();
        String baseName = fileObject.getName().getBaseName();
        return uri.substring(0, uri.indexOf(baseName));
    }

    // --- Formatting & Exceptions ---

    public static Object byteCountToDisplaySize(FileObject fo) throws FileSystemException {
        try (FileContent fc = fo.getContent()) {
            return byteCountToDisplaySize(fc.getSize());
        }
    }

    public static String byteCountToDisplaySize(long bytes) {
        // 1. Handle base case immediately
        if (bytes < 1024) {
            return Long.toString(bytes); // No unit suffix for bytes
        }

        // 2. Define units (Binary prefixes: K=1024, M=1024^2, etc.)
        String[] units = { "K", "M", "G", "T", "P", "E" };

        // 3. Calculate the exponent (base 1024)
        // Math.log(bytes) / Math.log(1024) gives us the power (1 = K, 2 = M, etc.)
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        exp = Math.min(exp, units.length);

        // 4. Calculate the displayed number
        double result = bytes / Math.pow(1024, exp);
        String unit = units[exp - 1];

        // 5. Format: 1 decimal place if < 10, otherwise 0.
        // Locale.US ensures we get a dot (.) separator, not a comma.
        if (result < 10) {
            return String.format(Locale.US, "%.1f%s", result, unit);
        } else {
            return String.format(Locale.US, "%.0f%s", result, unit);
        }
    }

    public static String getAllErrorMessages(Throwable t) {
        if (t == null) {
            return "null";
        }

        return Stream.iterate(t, Objects::nonNull, Throwable::getCause)
                .map(Throwable::getMessage)
                .filter(msg -> msg != null && !msg.isEmpty())
                .collect(Collectors.joining(" "));
    }
}
