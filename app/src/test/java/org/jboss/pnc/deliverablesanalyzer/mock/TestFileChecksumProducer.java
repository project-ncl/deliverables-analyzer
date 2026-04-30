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
