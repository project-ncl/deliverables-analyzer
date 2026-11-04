/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
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