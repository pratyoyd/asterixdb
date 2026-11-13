/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.api.http.server;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentMap;

import org.apache.asterix.common.api.IApplicationContext;
import org.apache.asterix.common.api.IRequestReference;
import org.apache.asterix.common.functions.ExternalFunctionLanguage;
import org.apache.asterix.common.metadata.DataverseName;
import org.apache.asterix.common.metadata.Namespace;
import org.apache.asterix.external.util.ExternalLibraryUtils;
import org.apache.commons.io.IOUtils;
import org.apache.hyracks.control.common.controllers.NCConfig;
import org.apache.hyracks.http.api.IServletRequest;
import org.apache.hyracks.http.api.IServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.HttpScheme;

public class NCUdfApiServlet extends AbstractNCUdfServlet {

    private final HttpScheme httpServerProtocol;
    private final int httpServerPort;

    private static final Logger LOGGER = LogManager.getLogger();

    public NCUdfApiServlet(ConcurrentMap<String, Object> ctx, String[] paths, IApplicationContext appCtx,
            HttpScheme httpServerProtocol, int httpServerPort) {
        super(ctx, paths, appCtx);
        this.httpServerProtocol = httpServerProtocol;
        this.httpServerPort = httpServerPort;
    }

    URI createDownloadURI(Path file) throws Exception {
        String host = appCtx.getServiceContext().getAppConfig().getString(NCConfig.Option.PUBLIC_ADDRESS);
        String path = paths[0].substring(0, servletPathLengths[0]) + GET_UDF_DIST_ENDPOINT + '/' + file.getFileName();
        return new URI(httpServerProtocol.toString(), null, host, httpServerPort, path, null, null);
    }

    @Override
    protected void distributeLibrary(LibraryUploadData uploadData, DataverseName libDv, String libName, String fileExt,
            ExternalFunctionLanguage language, MessageDigest digest, Namespace libNamespace,
            IRequestReference requestReference, IServletRequest request) throws Exception {
        URI downloadURI = cacheLibAndDistribute(uploadData, libDv, libName, fileExt, digest);
        doCreate(libNamespace, libName, language, ExternalLibraryUtils.digestToHexString(digest), downloadURI, true,
                getSysAuthHeader(), requestReference, request);
    }

    private URI cacheLibAndDistribute(LibraryUploadData uploadData, DataverseName libDv, String libName, String fileExt,
            MessageDigest digest) throws Exception {
        Path libraryTempFile = Files.createTempFile(workingDir, "lib_", '.' + fileExt);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Created temporary file " + libraryTempFile + " for library " + libDv.getCanonicalForm() + "."
                    + libName);
        }
        FileOutputStream libTmpOut = new FileOutputStream(libraryTempFile.toFile());
        try (OutputStream os = new DigestOutputStream(libTmpOut, digest);
                InputStream ui = new ByteBufInputStream((uploadData.fileUpload).getByteBuf())) {
            IOUtils.copyLarge(ui, os);
        }
        return createDownloadURI(libraryTempFile);
    }

    protected String getSysAuthHeader() {
        return sysAuthHeader;
    }

    @Override
    protected boolean isRequestPermitted(IServletRequest request, IServletResponse response) throws IOException {
        if (!isRequestOnLoopback(request)) {
            rejectForbidden(response);
            return false;
        }
        return true;
    }

    @Override
    protected void post(IServletRequest request, IServletResponse response) throws IOException {
        if (isRequestPermitted(request, response)) {
            handleModification(request, response, LibraryOperation.UPSERT);
        }
    }

    @Override
    protected void delete(IServletRequest request, IServletResponse response) throws IOException {
        if (isRequestPermitted(request, response)) {
            handleModification(request, response, LibraryOperation.DELETE);
        }
    }

}
