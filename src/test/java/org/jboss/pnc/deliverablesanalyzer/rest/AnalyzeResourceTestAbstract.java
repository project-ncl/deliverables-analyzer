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
import static org.junit.jupiter.api.Assertions.fail;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;

import javax.inject.Inject;

import org.jboss.pnc.api.dto.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.VerificationException;

/**
 * @author Jakub Bartecek
 */
public class AnalyzeResourceTestAbstract {
    protected static final Logger LOGGER = LoggerFactory.getLogger(AnalyzeResourceTestAbstract.class);

    protected final WireMockServer wiremock = new WireMockServer(options().port(8082));

    protected final String callbackRelativePath = "/callback";

    protected final String baseUrl = "http://localhost:8082";

    protected final String callbackUrl = baseUrl + callbackRelativePath;

    protected final Request callbackRequest;

    protected final String analyzeUrl = "/api/analyze";

    @Inject
    AnalyzeResource analyzeResource;

    protected AnalyzeResourceTestAbstract() throws MalformedURLException {
        callbackRequest = new Request("POST", new URL(callbackUrl));
    }

    protected String stubThreeArtsZip(int delayMilis) {
        wiremock.stubFor(
                any(urlEqualTo("/threeArts.zip")).willReturn(
                        aResponse().withFixedDelay(delayMilis).withBodyFile("threeArts.zip").withStatus(200)));
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
}
