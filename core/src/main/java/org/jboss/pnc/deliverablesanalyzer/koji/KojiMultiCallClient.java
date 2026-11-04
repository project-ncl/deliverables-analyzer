/*
 * SPDX-FileCopyrightText: Copyright © 2019 Red Hat, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.pnc.deliverablesanalyzer.koji;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.redhat.red.build.koji.KojiClient;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.KojiClientHelper;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveQuery;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildTypeInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiIdOrName;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import com.redhat.red.build.koji.model.xmlrpc.messages.Constants;

@ApplicationScoped
public class KojiMultiCallClient {

    @Inject
    KojiClient kojiClient;

    @Inject
    KojiClientHelper kojiClientHelper;

    public List<List<KojiArchiveInfo>> listArchives(List<KojiArchiveQuery> queries) throws KojiClientException {
        // Passing 'null' for anonymous session
        return kojiClientHelper.listArchives(queries, null);
    }

    public List<List<KojiTagInfo>> listTagsByIds(List<Integer> buildIds) throws KojiClientException {
        return kojiClientHelper.listTagsByIds(buildIds, null);
    }

    public List<KojiBuildInfo> getBuilds(List<KojiIdOrName> idsOrNames) throws KojiClientException {
        List<Object> args = formatArgs(idsOrNames);

        // MultiCall to get the base Build Info
        List<KojiBuildInfo> buildInfos = kojiClient.multiCall(Constants.GET_BUILD, args, KojiBuildInfo.class, null);
        if (buildInfos.isEmpty()) {
            return buildInfos;
        }

        // MultiCall to get the Build Type Info (Maven, Image, etc.)
        List<KojiBuildTypeInfo> buildTypeInfos = kojiClient
                .multiCall(Constants.GET_BUILD_TYPE, args, KojiBuildTypeInfo.class, null);

        if (buildInfos.size() != buildTypeInfos.size()) {
            throw new KojiClientException("Sizes of BuildInfo and BuildTypeInfo must be equal");
        }

        // Merge the types into the build infos (exact logic from old code)
        Iterator<KojiBuildInfo> itBuilds = buildInfos.iterator();
        Iterator<KojiBuildTypeInfo> itTypes = buildTypeInfos.iterator();
        while (itBuilds.hasNext()) {
            KojiBuildTypeInfo.addBuildTypeInfo(itTypes.next(), itBuilds.next());
        }

        return buildInfos;
    }

    public void enrichArchiveTypeInfo(List<KojiArchiveInfo> archiveInfos) throws KojiClientException {
        // Delegates to the base KojiClient method, passing null for an anonymous session
        kojiClient.enrichArchiveTypeInfo(archiveInfos, null);
    }

    private List<Object> formatArgs(List<KojiIdOrName> idsOrNames) throws KojiClientException {
        List<Object> args = new ArrayList<>(idsOrNames.size());
        for (KojiIdOrName idOrName : idsOrNames) {
            if (idOrName.getId() != null) {
                args.add(idOrName.getId());
            } else if (idOrName.getName() != null) {
                args.add(idOrName.getName());
            } else {
                throw new KojiClientException("Invalid KojiIdOrName: " + idOrName);
            }
        }
        return args;
    }
}
