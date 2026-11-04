/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.rest.control;

/**
 * Constants that hold the OIDC roles needed to talk to DelAn endpoints
 */
public class AuthorizationConstants {

    public final static String DELAN_ROLE = "pnc-app-delan-user";
    public final static String ADMIN_ROLE = "pnc-users-admin";
}
