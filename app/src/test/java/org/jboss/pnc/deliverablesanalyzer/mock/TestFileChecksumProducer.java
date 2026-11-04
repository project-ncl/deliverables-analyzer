/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.mock;

import java.lang.reflect.Field;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.http5.Http5FileProvider;
import org.jboss.pnc.deliverablesanalyzer.FileChecksumProducer;

import io.quarkus.test.Mock;

@Mock
@ApplicationScoped
public class TestFileChecksumProducer extends FileChecksumProducer {

    @Override
    @PostConstruct
    public void init() {

        // Initialize the VFS
        try {
            StandardFileSystemManager fileSystemManager = new StandardFileSystemManager();
            fileSystemManager.init();

            if (!fileSystemManager.hasProvider("http")) {
                fileSystemManager.addProvider("http", new Http5FileProvider());
            }
            if (!fileSystemManager.hasProvider("https")) {
                fileSystemManager.addProvider("https", new Http5FileProvider());
            }

            // Inject the manager into the parent class's private field
            // (We have to use reflection because the field is private)
            Field field = FileChecksumProducer.class.getDeclaredField("fileSystemManager");
            field.setAccessible(true);
            field.set(this, fileSystemManager);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Test VFS Manager", e);
        }
    }
}
