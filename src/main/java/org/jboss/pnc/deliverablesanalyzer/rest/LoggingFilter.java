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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.jboss.pnc.api.constants.MDCHeaderKeys;
import org.jboss.pnc.api.constants.MDCKeys;
import org.jboss.pnc.common.Strings;
import org.jboss.pnc.common.concurrent.Sequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Provider
public class LoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingFilter.class);
    private static final String REQUEST_EXECUTION_START = "request-execution-start";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        MDC.clear();
        Map<String, String> mdcContext = getContextMap();
        headerToMap(mdcContext, MDCHeaderKeys.REQUEST_CONTEXT, requestContext, () -> Sequence.nextId().toString());
        headerToMap(mdcContext, MDCHeaderKeys.PROCESS_CONTEXT, requestContext);
        headerToMap(mdcContext, MDCHeaderKeys.TMP, requestContext);
        headerToMap(mdcContext, MDCHeaderKeys.EXP, requestContext);
        headerToMap(mdcContext, MDCHeaderKeys.USER_ID, requestContext);

        MDC.setContextMap(mdcContext);

        requestContext.setProperty(REQUEST_EXECUTION_START, System.currentTimeMillis());

        UriInfo uriInfo = requestContext.getUriInfo();
        Request request = requestContext.getRequest();
        LOGGER.info("Requested {} {}.", request.getMethod(), uriInfo.getRequestUri());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Long startTime = (Long) requestContext.getProperty(REQUEST_EXECUTION_START);

        String took;
        if (startTime == null) {
            took = "-1";
        } else {
            took = Long.toString(System.currentTimeMillis() - startTime);
        }

        try (MDC.MDCCloseable mdcTook = MDC.putCloseable(MDCKeys.REQUEST_TOOK, took);
                MDC.MDCCloseable mdcStatus = MDC
                        .putCloseable(MDCKeys.RESPONSE_STATUS, Integer.toString(responseContext.getStatus()))) {
            LOGGER.debug("Completed {}, took: {}ms.", requestContext.getUriInfo().getPath(), took);
        }
    }

    private void headerToMap(
            Map<String, String> mdcContext,
            MDCHeaderKeys headerKeys,
            ContainerRequestContext requestContext) {
        String value = requestContext.getHeaderString(headerKeys.getHeaderName());
        mdcContext.put(headerKeys.getMdcKey(), value);
    }

    private void headerToMap(
            Map<String, String> map,
            MDCHeaderKeys headerKeys,
            ContainerRequestContext requestContext,
            Supplier<String> defaultValue) {
        String value = requestContext.getHeaderString(headerKeys.getHeaderName());
        if (Strings.isEmpty(value)) {
            map.put(headerKeys.getMdcKey(), defaultValue.get());
        } else {
            map.put(headerKeys.getMdcKey(), value);
        }
    }

    private static Map<String, String> getContextMap() {
        Map<String, String> context = MDC.getCopyOfContextMap();
        if (context == null) {
            context = new HashMap<>();
        }
        return context;
    }
}
