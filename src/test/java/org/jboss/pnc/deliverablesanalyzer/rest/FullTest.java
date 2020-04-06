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
package org.jboss.pnc.deliverablesanalyzer.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.jboss.pnc.build.finder.core.BuildFinderObjectMapper;
import org.jboss.pnc.deliverablesanalyzer.Version;
import org.jboss.pnc.deliverablesanalyzer.model.Build;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;

@QuarkusTest
public class FullTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FullTest.class);

    @Test
    public void testEverything() {
        final String url = System.getProperty("distribution.url");

        assertNotNull(url, "You must set property distribution.url");

        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.config = RestAssuredConfig.config()
                .objectMapperConfig(
                        new ObjectMapperConfig()
                                .jackson2ObjectMapperFactory((cls, charset) -> new BuildFinderObjectMapper()));

        String version = given().log()
                .all()
                .accept(MediaType.TEXT_PLAIN)
                .when()
                .get("/api/version")
                .then()
                .log()
                .all()
                .statusCode(Response.Status.OK.getStatusCode())
                .body(not(containsString("unknown")), containsString(Version.getVersion()))
                .extract()
                .response()
                .asString();

        LOGGER.info("Got version: {}", version);

        List<Build> builds = Arrays.asList(
                given().log()
                        .all()
                        .accept(MediaType.APPLICATION_JSON)
                        .queryParam("url", url)
                        .when()
                        .get("/api/analyze")
                        .then()
                        .log()
                        .ifError()
                        .assertThat()
                        .statusCode(Response.Status.OK.getStatusCode())
                        .extract()
                        .response()
                        .as(Build[].class));

        LOGGER.info("Got number builds: {}", builds.size());

        boolean isBuiltFromSource = given().log()
                .all()
                .accept(MediaType.APPLICATION_JSON)
                .queryParam("url", url)
                .when()
                .get("/api/analyze/built-from-source")
                .then()
                .log()
                .ifError()
                .assertThat()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .response()
                .jsonPath()
                .getBoolean("builtFromSource");

        LOGGER.info("Built from source: {}", isBuiltFromSource);
    }
}
