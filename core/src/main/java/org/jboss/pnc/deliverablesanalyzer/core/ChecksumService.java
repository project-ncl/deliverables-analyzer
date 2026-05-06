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

import static org.jboss.pnc.deliverablesanalyzer.utils.AnalyzerUtils.normalizePath;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksummedFile;

@ApplicationScoped
public class ChecksumService {

    private static final int BUFFER_SIZE = 8192;

    public ChecksummedFile checksum(FileObject fo, String root) throws IOException {
        MessageDigest sha256Digest = DigestUtils.getSha256Digest();
        MessageDigest sha1Digest = DigestUtils.getSha1Digest();
        MessageDigest md5Digest = DigestUtils.getMd5Digest();

        try (FileContent fc = fo.getContent(); InputStream is = fc.getInputStream()) {
            long fileSize = determineFileSize(fc);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;

            while ((read = is.read(buffer)) > 0) {
                sha256Digest.update(buffer, 0, read);
                sha1Digest.update(buffer, 0, read);
                md5Digest.update(buffer, 0, read);
            }

            String sha256 = Hex.encodeHexString(sha256Digest.digest());
            String sha1 = Hex.encodeHexString(sha1Digest.digest());
            String md5 = Hex.encodeHexString(md5Digest.digest());

            return ChecksummedFile.create(sha256, sha1, md5, normalizePath(fo, root), fileSize);
        }
    }

    private long determineFileSize(FileContent fc) throws FileSystemException {
        try {
            return fc.getSize();
        } catch (FileSystemException e) {
            throw new FileSystemException("Error determining file size. Does file " + fc.getFile() + " exist?", e);
        }
    }
}
