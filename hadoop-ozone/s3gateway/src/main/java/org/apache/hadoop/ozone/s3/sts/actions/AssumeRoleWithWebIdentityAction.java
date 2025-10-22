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

package org.apache.hadoop.ozone.s3.sts.actions;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.auth.AuthenticationProviderFactory;
import org.apache.hadoop.ozone.s3.sts.auth.AuthenticationRequest;
import org.apache.hadoop.ozone.s3.sts.auth.AuthenticationResult;
import org.apache.hadoop.ozone.s3.sts.model.AssumeRoleResponse;
import org.apache.hadoop.ozone.s3.sts.model.AssumeRoleWithWebIdentityRequest;
import org.apache.hadoop.ozone.s3.sts.model.Credentials;
import org.apache.hadoop.ozone.s3.sts.token.JWTTokenGenerator;
import org.apache.hadoop.ozone.s3.sts.token.STSCredentialGenerator;
import org.apache.hadoop.ozone.s3.sts.token.TemporaryCredentials;
import org.apache.hadoop.ozone.s3.sts.token.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for AssumeRoleWithWebIdentity action.
 */
public class AssumeRoleWithWebIdentityAction implements STSAction {

  private static final Logger LOG =
      LoggerFactory.getLogger(AssumeRoleWithWebIdentityAction.class);

  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

  private final AssumeRoleWithWebIdentityRequest request;
  private OzoneConfiguration config;
  private AuthenticationProviderFactory authProviderFactory;
  private STSCredentialGenerator credentialGenerator;
  private TokenStore tokenStore;
  private JWTTokenGenerator jwtGenerator;

  public AssumeRoleWithWebIdentityAction(
      AssumeRoleWithWebIdentityRequest request) {
    this.request = request;
  }

  @Override
  public void initialize(OzoneConfiguration conf,
      AuthenticationProviderFactory authFactory,
      STSCredentialGenerator credGen,
      TokenStore store,
      JWTTokenGenerator jwtGen) {
    this.config = conf;
    this.authProviderFactory = authFactory;
    this.credentialGenerator = credGen;
    this.tokenStore = store;
    this.jwtGenerator = jwtGen;
  }

  @Override
  public Response execute() {
    try {
      // Validate required parameters
      if (request.getWebIdentityToken() == null ||
          request.getWebIdentityToken().isEmpty()) {
        return createErrorResponse("MissingParameter",
            "WebIdentityToken is required");
      }

      if (request.getRoleArn() == null || request.getRoleArn().isEmpty()) {
        return createErrorResponse("MissingParameter",
            "RoleArn is required");
      }

      if (request.getRoleSessionName() == null ||
          request.getRoleSessionName().isEmpty()) {
        return createErrorResponse("MissingParameter",
            "RoleSessionName is required");
      }

      // Authenticate using web identity token
      AuthenticationRequest authRequest =
          AuthenticationRequest.newBuilder()
              .setWebIdentityToken(request.getWebIdentityToken())
              .build();

      AuthenticationResult authResult =
          authProviderFactory.authenticate(authRequest, "OIDC");

      if (!authResult.isSuccess()) {
        LOG.warn("Authentication failed: {}", authResult.getErrorMessage());
        return createErrorResponse("InvalidIdentityToken",
            authResult.getErrorMessage());
      }

      // Generate temporary credentials
      Map<String, String> attributes = new HashMap<>();
      attributes.putAll(authResult.getAttributes());
      attributes.put("provider", "WEB_IDENTITY");

      TemporaryCredentials tempCreds = credentialGenerator.generateCredentials(
          authResult.getPrincipal(),
          request.getRoleArn(),
          request.getRoleSessionName(),
          request.getDurationSeconds(),
          attributes
      );

      // Generate JWT token
      String jwtToken = jwtGenerator.generateToken(tempCreds);

      // Build response
      AssumeRoleResponse response = buildResponse(tempCreds, jwtToken);

      LOG.info("AssumeRoleWithWebIdentity succeeded for principal: {}",
          authResult.getPrincipal());

      return Response.ok(response, MediaType.APPLICATION_XML).build();

    } catch (Exception e) {
      LOG.error("AssumeRoleWithWebIdentity failed", e);
      return createErrorResponse("InternalError",
          "An error occurred: " + e.getMessage());
    }
  }

  private AssumeRoleResponse buildResponse(TemporaryCredentials tempCreds,
      String jwtToken) {
    AssumeRoleResponse response = new AssumeRoleResponse();

    // Create credentials
    Credentials credentials = new Credentials(
        tempCreds.getAccessKeyId(),
        tempCreds.getSecretAccessKey(),
        tempCreds.getSessionToken(),
        ISO_FORMATTER.format(tempCreds.getExpiration())
    );

    // Create assumed role user
    AssumeRoleResponse.AssumedRoleUser assumedRoleUser =
        new AssumeRoleResponse.AssumedRoleUser(
            tempCreds.getAccessKeyId(),
            tempCreds.getRoleArn() != null ?
                tempCreds.getRoleArn() :
                "arn:ozone:iam::assumed-role"
        );

    // Create result
    AssumeRoleResponse.AssumeRoleResult result =
        new AssumeRoleResponse.AssumeRoleResult();
    result.setCredentials(credentials);
    result.setAssumedRoleUser(assumedRoleUser);
    result.setJwtToken(jwtToken);

    response.setAssumeRoleResult(result);

    return response;
  }

  private Response createErrorResponse(String code, String message) {
    String errorXml = String.format(
        "<ErrorResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">" +
        "<Error><Type>Sender</Type><Code>%s</Code><Message>%s</Message></Error>" +
        "<RequestId>%s</RequestId></ErrorResponse>",
        code, message, java.util.UUID.randomUUID().toString()
    );
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(errorXml)
        .type(MediaType.APPLICATION_XML)
        .build();
  }

  @Override
  public String getActionName() {
    return "AssumeRoleWithWebIdentity";
  }
}

