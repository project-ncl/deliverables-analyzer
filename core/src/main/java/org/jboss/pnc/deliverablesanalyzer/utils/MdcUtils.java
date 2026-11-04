/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.utils;

import java.util.HashMap;
import java.util.Map;

import org.jboss.pnc.api.constants.MDCHeaderKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class MdcUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(MdcUtils.class);

    private MdcUtils() {
        // Prevent instantiation
    }

    /**
     * Populate the result map with mdc keys and values if present
     *
     * @param result map where the mdc keys and values are put
     * @param mdcMap original mdc map from MDC
     * @param mdcHeaderKeys keys to add to the result map if present
     */
    public static void putMdcToResultMap(
            Map<String, String> result,
            Map<String, String> mdcMap,
            MDCHeaderKeys mdcHeaderKeys) {
        if (mdcMap.get(mdcHeaderKeys.getMdcKey()) != null) {
            result.put(mdcHeaderKeys.getHeaderName(), mdcMap.get(mdcHeaderKeys.getMdcKey()));
        } else {
            LOGGER.warn("MDC value {} missing", mdcHeaderKeys.getMdcKey());
        }
    }

    public static Map<String, String> mdcToMapWithHeaderKeys() {
        Map<String, String> result = new HashMap<>();
        Map<String, String> mdcMap = MDC.getCopyOfContextMap();

        if (mdcMap == null) {
            LOGGER.debug("MDC map is completely empty. Skipping header propagation.");
            return result;
        }

        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.PROCESS_CONTEXT);
        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.TMP);
        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.EXP);
        putMdcToResultMap(result, mdcMap, MDCHeaderKeys.USER_ID);
        return result;
    }
}
