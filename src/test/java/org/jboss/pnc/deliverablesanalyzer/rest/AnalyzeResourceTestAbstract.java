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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.jboss.pnc.api.dto.Request.Method.POST;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.deliverablesanalyzer.model.AnalyzeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.VerificationException;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;

import jakarta.inject.Inject;

/**
 * @author Jakub Bartecek
 */
public class AnalyzeResourceTestAbstract {
    private static final String CONFIG_FILE = "custom_config.json";

    protected static final int PORT = 8082;

    protected static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeResourceTestAbstract.class);

    protected final WireMockServer wiremock = new WireMockServer(
            options().port(PORT).notifier(new Slf4jNotifier(true)));

    protected final String callbackRelativePath = "/callback";

    protected final String baseUrl = "http://localhost:" + PORT;

    protected final String callbackUrl = baseUrl + callbackRelativePath;

    protected final Request callbackRequest;

    protected final String analyzeUrl = "/api/analyze";

    protected String testConfigJson = null;

    @Inject
    AnalyzeResource analyzeResource;

    protected AnalyzeResourceTestAbstract() throws URISyntaxException {
        callbackRequest = new Request(POST, new URI(callbackUrl));

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            assertNotNull(is);
            testConfigJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOGGER.debug("Found TEST configuration: {}", testConfigJson);
        } catch (IOException e) {
            LOGGER.error("Could not read the TEST configuration", e);
        }
    }

    protected String stubThreeArtsZip(int milliseconds) {
        wiremock.stubFor(
                any(urlEqualTo("/threeArts.zip")).willReturn(
                        aResponse().withFixedDelay(milliseconds).withBodyFile("threeArts.zip").withStatus(HTTP_OK)));
        return baseUrl + "/threeArts.zip";
    }

    protected void verifyCallback(Runnable r) throws InterruptedException {
        long oldTime = new Date().getTime();
        while ((new Date().getTime() - oldTime) < 15000) {
            try {
                r.run();
                return;
            } catch (VerificationException e) {
                Thread.sleep(300);
            }
        }

        fail("Expected callback was not delivered!");
    }

    protected String getAnalysisId(String response) throws JsonProcessingException {
        return getAnalyzeResponse(response).getId();
    }

    protected AnalyzeResponse getAnalyzeResponse(String response) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(response, AnalyzeResponse.class);
    }
}
