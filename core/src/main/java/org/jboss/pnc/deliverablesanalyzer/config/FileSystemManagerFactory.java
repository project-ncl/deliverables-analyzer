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
package org.jboss.pnc.deliverablesanalyzer.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;

import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.http5.Http5FileProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class FileSystemManagerFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemManagerFactory.class);

    @Produces
    @ApplicationScoped
    public FileSystemManager createManager() {
        try {
            StandardFileSystemManager manager = new StandardFileSystemManager();
            manager.init();
            if (!manager.hasProvider("http"))
                manager.addProvider("http", new Http5FileProvider());
            if (!manager.hasProvider("https"))
                manager.addProvider("https", new Http5FileProvider());

            LOGGER.info(
                    "Initialized file system manager {} with schemes: {}",
                    manager.getClass().getSimpleName(),
                    String.join(", ", manager.getSchemes()));

            return manager;
        } catch (FileSystemException e) {
            throw new RuntimeException("Failed to initialize file system manager", e);
        }
    }

    public void closeManager(@Disposes FileSystemManager manager) {
        manager.close();
    }
}
