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

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.eclipse.packager.rpm.RpmSignatureTag;
import org.eclipse.packager.rpm.RpmTag;
import org.eclipse.packager.rpm.coding.PayloadCoding;
import org.eclipse.packager.rpm.parse.InputHeader;
import org.eclipse.packager.rpm.parse.RpmInputStream;
import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.ChecksumType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Optional;

import static org.jboss.pnc.deliverablesanalyzer.utils.AnalyzerUtils.normalizePath;

@ApplicationScoped
public class ChecksumService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChecksumService.class);

    private static final int BUFFER_SIZE = 8192;

    public Checksum checksum(FileObject fo, String root) throws IOException {
        FileName filename = fo.getName();

        if ("rpm".equals(filename.getExtension())) {
            return checksumRpm(fo, root);
        } else {
            return checksumStandard(fo, root);
        }
    }

    private Checksum checksumStandard(FileObject fo, String root) throws IOException {
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

            return Checksum.create(sha256, sha1, md5, normalizePath(fo, root), fileSize);
        }
    }

    private Checksum checksumRpm(FileObject fo, String root) throws IOException {
        Checksum checksum;

        try (FileContent fc = fo.getContent();
                InputStream is = fc.getInputStream();
                RpmInputStream in = new RpmInputStream(is)) {
            long fileSize = determineFileSize(fc);

            if (LOGGER.isDebugEnabled()) {
                logRpmDebugInfo(in, fo.getName());
            }

            LOGGER.debug("Handle checksum type {} for RPM {}", ChecksumType.SHA256.getAlgorithm(), fo.getName());

            String sha256 = extractRpmSignature(
                    in,
                    RpmSignatureTag.SHA256HEADER,
                    ChecksumType.SHA256.getAlgorithm(),
                    fo);
            String sha1 = extractRpmSignature(in, RpmSignatureTag.SHA1HEADER, ChecksumType.SHA1.getAlgorithm(), fo);
            String md5 = extractRpmSignature(in, RpmSignatureTag.MD5, ChecksumType.MD5.getAlgorithm(), fo);
            checksum = Checksum.create(sha256, sha1, md5, normalizePath(fo, root), fileSize);
        }
        return checksum;
    }

    private String extractRpmSignature(RpmInputStream in, RpmSignatureTag tag, String algorithm, FileObject fo)
            throws IOException {
        Object hash = in.getSignatureHeader().getTag(tag);
        if (!(hash instanceof byte[])) {
            LOGGER.warn("Missing {} for {}", algorithm, fo);
            if (tag.equals(RpmSignatureTag.MD5)) {
                throw new IOException("Missing " + algorithm + " for " + fo);
            }
            return null;
        }
        return Hex.encodeHexString((byte[]) hash);
    }

    private long determineFileSize(FileContent fc) throws FileSystemException {
        try {
            return fc.getSize();
        } catch (FileSystemException e) {
            throw new FileSystemException("Error determining file size. Does file " + fc.getFile() + " exist?", e);
        }
    }

    private void logRpmDebugInfo(RpmInputStream in, FileName filename) throws IOException {
        LOGGER.debug("Got RPM: {}", filename);
        InputHeader<RpmTag> payloadHeader = in.getPayloadHeader();
        Optional<Object> payloadCodingHeader = payloadHeader.getOptionalTag(RpmTag.PAYLOAD_CODING);

        if (payloadCodingHeader.isPresent()) {
            String payloadCoding = (String) payloadCodingHeader.get();
            PayloadCoding coding = PayloadCoding.fromValue(payloadCoding).orElse(PayloadCoding.NONE);
            LOGGER.debug("Payload for RPM {} is compressed using: {}", in.getLead().getName(), coding.getValue());
        }
    }
}
