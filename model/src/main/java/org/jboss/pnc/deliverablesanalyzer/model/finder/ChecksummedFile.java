/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import java.io.Serial;
import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChecksummedFile implements Serializable {
    @Serial
    private static final long serialVersionUID = -7347509034711302799L;

    private String sha256Value;
    private String sha1Value;
    private String md5Value;

    @JsonIgnore
    private String filename;

    @JsonIgnore
    private long fileSize;

    public ChecksummedFile(String sha256Value, String sha1Value, String md5Value, LocalFile localFile) {
        this.sha256Value = sha256Value;
        this.sha1Value = sha1Value;
        this.md5Value = md5Value;
        this.filename = localFile.filename();
        this.fileSize = localFile.size();
    }

    public static ChecksummedFile create(String sha256Value, String sha1Value, String md5Value, LocalFile localFile) {
        return new ChecksummedFile(sha256Value, sha1Value, md5Value, localFile);
    }

    public static ChecksummedFile create(
            String sha256Value,
            String sha1Value,
            String md5Value,
            String filename,
            long fileSize) {
        return new ChecksummedFile(sha256Value, sha1Value, md5Value, filename, fileSize);
    }
}
