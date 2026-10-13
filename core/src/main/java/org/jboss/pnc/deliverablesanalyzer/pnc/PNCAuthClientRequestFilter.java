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
package org.jboss.pnc.deliverablesanalyzer.pnc;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;

import java.io.IOException;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;

import org.jboss.pnc.deliverablesanalyzer.utils.MdcUtils;
import org.jboss.pnc.quarkus.client.auth.runtime.PNCClientAuth;

@ApplicationScoped
public class PNCAuthClientRequestFilter implements ClientRequestFilter {

    @Inject
    PNCClientAuth pncClientAuth;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        MultivaluedMap<String, Object> reqHeaders = requestContext.getHeaders();

        Map<String, String> headers = MdcUtils.mdcToMapWithHeaderKeys();
        if (headers != null) {
            headers.forEach(reqHeaders::putSingle);
        }

        String authHeader = pncClientAuth.getHttpAuthorizationHeaderValueWithCachedToken();
        if (authHeader != null && !authHeader.isBlank()) {
            reqHeaders.putSingle(AUTHORIZATION, authHeader);
        }
    }
}