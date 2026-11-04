/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Getter;

@Getter
public enum ChecksumType {
    MD5(0, "MD5"), SHA1(1, "SHA-1"), SHA256(2, "SHA-256");

    private final int value;
    private final String algorithm;

    private static final Map<Integer, ChecksumType> BY_VALUE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(ChecksumType::getValue, Function.identity()));

    ChecksumType(int value, String algorithm) {
        this.value = value;
        this.algorithm = algorithm;
    }

    public static ChecksumType fromInteger(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }

        ChecksumType type = BY_VALUE.get(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown value for checksum type: " + value);
        }
        return type;
    }
}
