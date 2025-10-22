/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.s3.sts;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.ext.Provider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.OzoneConfigurationHolder;
import org.apache.hadoop.ozone.s3.exception.OS3Exception;
import org.apache.hadoop.ozone.s3.exception.S3ErrorTable;
import org.apache.hadoop.ozone.s3.signature.SignatureInfo;
import org.apache.hadoop.ozone.s3.sts.token.TemporaryCredentials;
import org.apache.hadoop.ozone.s3.sts.token.TokenStore;
import org.apache.hadoop.ozone.s3.sts.token.TokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter to validate STS session tokens.
 */
@Provider
@PreMatching
@Priority(STSAuthenticationFilter.PRIORITY)
public class STSAuthenticationFilter implements ContainerRequestFilter {
  
  public static final int PRIORITY = 55; // After AuthorizationFilter (50)
  public static final String X_AMZ_SECURITY_TOKEN = "x-amz-security-token";
  public static final String STS_PRINCIPAL_ATTRIBUTE = "sts.principal";

  private static final Logger LOG =
      LoggerFactory.getLogger(STSAuthenticationFilter.class);

  @Inject
  private SignatureInfo signatureInfo;

  private TokenValidator tokenValidator;
  private boolean stsEnabled;

  @VisibleForTesting
  public void setSignatureInfo(SignatureInfo signatureInfo) {
    this.signatureInfo = signatureInfo;
  }

  @Override
  public void filter(ContainerRequestContext context) throws IOException {
    // Check if STS is enabled
    if (tokenValidator == null) {
      initializeTokenValidator();
    }

    if (!stsEnabled) {
      return; // STS not enabled, skip validation
    }

    // Check if this is a temporary credential
    String accessKeyId = signatureInfo.getAwsAccessId();
    if (accessKeyId == null || !accessKeyId.startsWith("STS-")) {
      return; // Not a temporary credential, skip validation
    }

    // Extract session token from header
    String sessionToken = context.getHeaderString(X_AMZ_SECURITY_TOKEN);
    if (sessionToken == null || sessionToken.isEmpty()) {
      LOG.warn("STS access key used without session token: {}", accessKeyId);
      throw wrapOS3Exception(S3ErrorTable.newError(
          S3ErrorTable.MALFORMED_HEADER,
          "X-Amz-Security-Token header is required for STS credentials"));
    }

    // Validate session token
    TokenValidator.ValidationResult result =
        tokenValidator.validate(accessKeyId, sessionToken);

    if (!result.isValid()) {
      LOG.warn("Invalid STS session token for access key: {}. Error: {}",
          accessKeyId, result.getErrorMessage());
      throw wrapOS3Exception(S3ErrorTable.newError(
          S3ErrorTable.ACCESS_DENIED,
          "Invalid or expired session token"));
    }

    // Store principal in request context for authorization
    TemporaryCredentials credentials = result.getCredentials().orElse(null);
    if (credentials != null) {
      context.setProperty(STS_PRINCIPAL_ATTRIBUTE, credentials.getPrincipal());
      LOG.debug("STS authentication successful for principal: {}",
          credentials.getPrincipal());
    }
  }

  private synchronized void initializeTokenValidator() {
    if (tokenValidator != null) {
      return;
    }

    try {
      OzoneConfiguration config = OzoneConfigurationHolder.configuration();
      stsEnabled = config.getBoolean(
          STSConfigKeys.OZONE_STS_ENABLED,
          STSConfigKeys.OZONE_STS_ENABLED_DEFAULT);

      if (stsEnabled) {
        TokenStore tokenStore = new TokenStore(config);
        tokenValidator = new TokenValidator(config, tokenStore);
        LOG.info("STS authentication filter initialized");
      } else {
        LOG.info("STS is disabled, filter inactive");
      }
    } catch (Exception e) {
      LOG.error("Failed to initialize STS authentication filter", e);
      stsEnabled = false;
    }
  }

  private IOException wrapOS3Exception(OS3Exception ex) {
    IOException ioException = new IOException(ex.getMessage());
    ioException.initCause(ex);
    return ioException;
  }
}

