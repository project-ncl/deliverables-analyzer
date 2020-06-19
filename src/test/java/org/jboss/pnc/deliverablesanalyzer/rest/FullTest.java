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
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.jboss.pnc.deliverablesanalyzer.Version;
import org.jboss.pnc.deliverablesanalyzer.model.FinderResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;

@QuarkusTest
public class FullTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(FullTest.class);

    private static final String URL = System.getProperty("distribution.url");

    @BeforeAll
    public static void init() {
        assertNotNull(URL, "You must set property distribution.url");

        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        RestAssured.config = RestAssuredConfig.config()
                .objectMapperConfig(
                        new ObjectMapperConfig().jackson2ObjectMapperFactory(
                                (cls, charset) -> new ObjectMapper().findAndRegisterModules()
                                        .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
                                        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)));
    }

    @Test
    public void testVersion() {
        String version = given().log()
                .all()
                .accept(MediaType.TEXT_PLAIN)
                .when()
                .get("/api/version")
                .then()
                .log()
                .all()
                .statusCode(Response.Status.OK.getStatusCode())
                .body(
                        not(is(emptyOrNullString())),
                        not(containsString("unknown")),
                        containsString(Version.getVersion()))
                .extract()
                .response()
                .asString();

        LOGGER.info("Version: {}", version);
    }

    @Test
    public void testCreated() {
        String location = given().log()
                .all()
                .redirects()
                .follow(false)
                .accept(MediaType.TEXT_PLAIN)
                .formParam("url", URL)
                .when()
                .post("/api/analyze")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(Response.Status.CREATED.getStatusCode())
                .extract()
                .header("Location");

        assertThat(location, is(not(emptyOrNullString())));

        await().atMost(Duration.ofMinutes(10L))
                .pollInterval(Duration.ofSeconds(30L))
                .untilAsserted(
                        () -> RestAssured.when().get(location).then().statusCode(Response.Status.OK.getStatusCode()));

        FinderResult result = given().log()
                .all()
                .accept(MediaType.APPLICATION_JSON)
                .when()
                .get(location)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .response()
                .as(FinderResult.class);

        assertThat(result.getBuilds().size(), is(greaterThan(0)));
    }
}
