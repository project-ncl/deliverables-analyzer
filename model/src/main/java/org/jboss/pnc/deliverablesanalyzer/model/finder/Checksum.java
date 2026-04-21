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
package org.jboss.pnc.deliverablesanalyzer.model.finder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Checksum implements Comparable<Checksum>, Serializable {
    @Serial
    private static final long serialVersionUID = -7347509034711302799L;

    private String sha256Value;
    private String sha1Value;
    private String md5Value;

    @JsonIgnore
    private String filename;

    @JsonIgnore
    private long fileSize;

    public Checksum(String sha256Value, String sha1Value, String md5Value, LocalFile localFile) {
        this.sha256Value = sha256Value;
        this.sha1Value = sha1Value;
        this.md5Value = md5Value;
        this.filename = localFile.filename();
        this.fileSize = localFile.size();
    }

    public static Checksum create(String sha256Value, String sha1Value, String md5Value, LocalFile localFile) {
        return new Checksum(sha256Value, sha1Value, md5Value, localFile);
    }

    public static Checksum create(
            String sha256Value,
            String sha1Value,
            String md5Value,
            String filename,
            long fileSize) {
        return new Checksum(sha256Value, sha1Value, md5Value, filename, fileSize);
    }

    /**
     * Implements comparison logic: 1. Type (Ordinal) 2. Value (String) 3. Filename (String) 4. FileSize (Long)
     */
    @Override
    public int compareTo(Checksum other) {
        if (other == null) {
            return 1;
        }

        return Comparator.comparing(Checksum::getSha256Value)
                .thenComparing(Checksum::getFilename)
                .thenComparingLong(Checksum::getFileSize)
                .compare(this, other);
    }
}
