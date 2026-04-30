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
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import org.jboss.pnc.api.deliverablesanalyzer.dto.AnalyzePayload;
import org.jboss.pnc.deliverablesanalyzer.rest.control.AnalyzeService;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class AnalyzeResourceTest {

    @InjectMock
    AnalyzeService analyzeService;

    @Test
    void testAnalyzeEndpointSuccess() {
        // Given
        String operationId = "test-op-id";
        AnalyzePayload payload = AnalyzePayload.builder()
                .operationId(operationId)
                .urls(List.of("http://example.com/file.zip"))
                .build();

        when(analyzeService.analyze(any(AnalyzePayload.class))).thenReturn(operationId);

        // When & Then
        given().contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/api/analyze")
                .then()
                .statusCode(200)
                .body("id", equalTo(operationId))
                .body("cancelRequest.method", equalTo("POST"))
                .body("cancelRequest.uri", containsString("/analyze/" + operationId + "/cancel"));

        verify(analyzeService).analyze(any(AnalyzePayload.class));
    }

    @Test
    void testAnalyzeEndpointBadRequest() {
        // Given: Empty payload (missing required fields)
        AnalyzePayload payload = AnalyzePayload.builder().build();
        when(analyzeService.analyze(any(AnalyzePayload.class)))
                .thenThrow(new BadRequestException("No URL was specified"));

        // When & Then
        // Expecting 400 Bad Request
        given().contentType(ContentType.JSON).body(payload).when().post("/api/analyze").then().statusCode(400);
    }

    @Test
    void testCancelEndpointSuccess() {
        // Given
        String id = "valid-id";
        when(analyzeService.cancel(id)).thenReturn(true);

        // When & Then
        given().contentType(ContentType.JSON)
                .when()
                .post("/api/analyze/{id}/cancel", id)
                .then()
                .statusCode(200)
                .body(equalTo("true"));
    }

    @Test
    void testCancelEndpointNotFound() {
        // Given
        String id = "missing-id";
        when(analyzeService.cancel(id)).thenThrow(new NotFoundException("Analysis not found"));

        // When & Then
        given().contentType(ContentType.JSON).when().post("/api/analyze/{id}/cancel", id).then().statusCode(404);
    }
}
