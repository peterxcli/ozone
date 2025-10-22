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

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.auth.AuthenticationProviderFactory;
import org.apache.hadoop.ozone.s3.sts.token.JWTTokenGenerator;
import org.apache.hadoop.ozone.s3.sts.token.STSCredentialGenerator;
import org.apache.hadoop.ozone.s3.sts.token.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for GetCallerIdentity action.
 */
public class GetCallerIdentityAction implements STSAction {

  private static final Logger LOG =
      LoggerFactory.getLogger(GetCallerIdentityAction.class);

  private final SecurityContext securityContext;

  public GetCallerIdentityAction(SecurityContext securityContext) {
    this.securityContext = securityContext;
  }

  @Override
  public void initialize(OzoneConfiguration conf,
      AuthenticationProviderFactory authProviderFactory,
      STSCredentialGenerator credentialGenerator,
      TokenStore store,
      JWTTokenGenerator jwtGenerator) {
    // No initialization needed for GetCallerIdentity
  }

  @Override
  public Response execute() {
    try {
      String principal = securityContext != null &&
          securityContext.getUserPrincipal() != null ?
          securityContext.getUserPrincipal().getName() : "anonymous";

      String responseXml = String.format(
          "<GetCallerIdentityResponse " +
          "xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">" +
          "<GetCallerIdentityResult>" +
          "<Arn>arn:ozone:iam::user/%s</Arn>" +
          "<UserId>%s</UserId>" +
          "<Account>ozone</Account>" +
          "</GetCallerIdentityResult>" +
          "<ResponseMetadata><RequestId>%s</RequestId></ResponseMetadata>" +
          "</GetCallerIdentityResponse>",
          principal, principal, java.util.UUID.randomUUID().toString()
      );

      LOG.info("GetCallerIdentity for principal: {}", principal);

      return Response.ok(responseXml, MediaType.APPLICATION_XML).build();

    } catch (Exception e) {
      LOG.error("GetCallerIdentity failed", e);
      return createErrorResponse("InternalError",
          "An error occurred: " + e.getMessage());
    }
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
    return "GetCallerIdentity";
  }
}

