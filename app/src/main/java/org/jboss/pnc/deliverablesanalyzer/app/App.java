/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;

@ApplicationPath("/api")
@ApplicationScoped
@OpenAPIDefinition(
        info = @Info(
                title = "Deliverables Analyzer",
                version = "<version>",
                description = "Analyzes deliverables.",
                contact = @Contact(name = "Red Hat, Inc.", url = "https://www.redhat.com/"),
                license = @License(name = "ASL 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0.txt")))
public class App extends Application {
}
