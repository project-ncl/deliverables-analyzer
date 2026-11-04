/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import java.io.Serial;
import java.io.Serializable;

import org.infinispan.protostream.annotations.ProtoField;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize
public record LocalFile(String filename, @ProtoField(defaultValue = "0") long size) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
