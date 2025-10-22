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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.ws.rs.BeanParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.OzoneConfigurationHolder;
import org.apache.hadoop.ozone.s3.sts.actions.AssumeRoleWithWebIdentityAction;
import org.apache.hadoop.ozone.s3.sts.actions.GetCallerIdentityAction;
import org.apache.hadoop.ozone.s3.sts.actions.STSAction;
import org.apache.hadoop.ozone.s3.sts.auth.AuthenticationProviderFactory;
import org.apache.hadoop.ozone.s3.sts.model.AssumeRoleWithWebIdentityRequest;
import org.apache.hadoop.ozone.s3.sts.model.STSRequest;
import org.apache.hadoop.ozone.s3.sts.token.JWTTokenGenerator;
import org.apache.hadoop.ozone.s3.sts.token.STSCredentialGenerator;
import org.apache.hadoop.ozone.s3.sts.token.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS STS (Security Token Service) endpoint.
 * Provides temporary credentials via multiple authentication mechanisms.
 */
@Path("/")
public class STSEndpoint {

  private static final Logger LOG = LoggerFactory.getLogger(STSEndpoint.class);

  @Inject
  private OzoneConfigurationHolder configurationHolder;

  @Context
  private SecurityContext securityContext;

  private OzoneConfiguration config;
  private boolean enabled;
  private AuthenticationProviderFactory authProviderFactory;
  private TokenStore tokenStore;
  private JWTTokenGenerator jwtGenerator;
  private STSCredentialGenerator credentialGenerator;

  @PostConstruct
  public void init() {
    try {
      config = OzoneConfigurationHolder.configuration();
      enabled = config.getBoolean(
          STSConfigKeys.OZONE_STS_ENABLED,
          STSConfigKeys.OZONE_STS_ENABLED_DEFAULT);

      if (!enabled) {
        LOG.info("STS service is disabled");
        return;
      }

      // Initialize components
      tokenStore = new TokenStore(config);
      jwtGenerator = new JWTTokenGenerator(config);
      credentialGenerator = new STSCredentialGenerator(
          config, tokenStore, jwtGenerator);
      authProviderFactory = new AuthenticationProviderFactory(config);

      LOG.info("STS endpoint initialized successfully");

    } catch (Exception e) {
      LOG.error("Failed to initialize STS endpoint", e);
      enabled = false;
    }
  }

  @PreDestroy
  public void cleanup() {
    if (authProviderFactory != null) {
      authProviderFactory.close();
    }
    if (tokenStore != null) {
      tokenStore.clear();
    }
    LOG.info("STS endpoint cleaned up");
  }

  /**
   * Handle POST requests for STS actions.
   */
  @POST
  @Produces(MediaType.APPLICATION_XML)
  public Response handlePost(@BeanParam STSRequest baseRequest) {
    if (!enabled) {
      return createErrorResponse("ServiceUnavailable",
          "STS service is not enabled");
    }

    String action = baseRequest.getAction();
    if (action == null || action.isEmpty()) {
      return createErrorResponse("MissingAction",
          "Action parameter is required");
    }

    LOG.debug("Handling STS action: {}", action);

    try {
      STSAction stsAction = createAction(action, baseRequest);
      if (stsAction == null) {
        return createErrorResponse("InvalidAction",
            "Unknown action: " + action);
      }

      // Initialize action with dependencies
      stsAction.initialize(config, authProviderFactory, credentialGenerator,
          tokenStore, jwtGenerator);

      // Execute action
      return stsAction.execute();

    } catch (Exception e) {
      LOG.error("Error handling STS action: {}", action, e);
      return createErrorResponse("InternalError",
          "An error occurred: " + e.getMessage());
    }
  }

  /**
   * Handle GET requests for STS actions (for compatibility).
   */
  @GET
  @Produces(MediaType.APPLICATION_XML)
  public Response handleGet(@BeanParam STSRequest baseRequest) {
    // Delegate to POST handler
    return handlePost(baseRequest);
  }

  private STSAction createAction(String action, STSRequest baseRequest) {
    switch (action) {
    case "AssumeRoleWithWebIdentity":
      return new AssumeRoleWithWebIdentityAction(
          (AssumeRoleWithWebIdentityRequest) convertRequest(
              baseRequest, AssumeRoleWithWebIdentityRequest.class));

    case "GetCallerIdentity":
      return new GetCallerIdentityAction(securityContext);

    // Add more actions as implemented
    // case "AssumeRole":
    //   return new AssumeRoleAction((AssumeRoleRequest) convertRequest(
    //       baseRequest, AssumeRoleRequest.class));
    // case "GetSessionToken":
    //   return new GetSessionTokenAction(...);
    // case "AssumeRoleWithSAML":
    //   return new AssumeRoleWithSAMLAction(...);

    default:
      LOG.warn("Unknown or unimplemented STS action: {}", action);
      return null;
    }
  }

  private STSRequest convertRequest(STSRequest source, Class<?> targetClass) {
    // Simple conversion - in production, use a proper object mapper
    // For now, just return the source as the specific type
    // This works because JAX-RS will inject the right type
    try {
      STSRequest target = (STSRequest) targetClass.getDeclaredConstructor()
          .newInstance();
      target.setAction(source.getAction());
      target.setVersion(source.getVersion());
      target.setDurationSeconds(source.getDurationSeconds());
      return target;
    } catch (Exception e) {
      LOG.error("Error converting request", e);
      return source;
    }
  }

  private Response createErrorResponse(String code, String message) {
    String errorXml = String.format(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<ErrorResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">" +
        "<Error>" +
        "<Type>Sender</Type>" +
        "<Code>%s</Code>" +
        "<Message>%s</Message>" +
        "</Error>" +
        "<RequestId>%s</RequestId>" +
        "</ErrorResponse>",
        code, message, java.util.UUID.randomUUID().toString()
    );
    
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(errorXml)
        .type(MediaType.APPLICATION_XML)
        .build();
  }

  /**
   * Health check endpoint.
   */
  @GET
  @Path("/health")
  @Produces(MediaType.TEXT_PLAIN)
  public Response health() {
    if (enabled) {
      return Response.ok("STS service is running").build();
    } else {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity("STS service is disabled").build();
    }
  }
}

