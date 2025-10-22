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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import javax.ws.rs.core.Response;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.apache.hadoop.ozone.s3.sts.auth.AuthenticationProviderFactory;
import org.apache.hadoop.ozone.s3.sts.model.AssumeRoleWithWebIdentityRequest;
import org.apache.hadoop.ozone.s3.sts.token.JWTTokenGenerator;
import org.apache.hadoop.ozone.s3.sts.token.STSCredentialGenerator;
import org.apache.hadoop.ozone.s3.sts.token.TokenStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AssumeRoleWithWebIdentityAction.
 */
public class TestAssumeRoleWithWebIdentityAction {

  private OzoneConfiguration config;
  private AuthenticationProviderFactory authFactory;
  private STSCredentialGenerator credentialGenerator;
  private TokenStore tokenStore;
  private JWTTokenGenerator jwtGenerator;

  @BeforeEach
  public void setup() throws IOException {
    config = new OzoneConfiguration();
    config.set(STSConfigKeys.OZONE_STS_AUTH_PROVIDERS, "IDP");
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_ISSUER,
        "https://accounts.google.com");
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_CLIENT_ID, "test-client");

    tokenStore = new TokenStore(config);
    jwtGenerator = new JWTTokenGenerator(config);
    credentialGenerator = new STSCredentialGenerator(
        config, tokenStore, jwtGenerator);
    authFactory = new AuthenticationProviderFactory(config);
  }

  @AfterEach
  public void cleanup() {
    if (authFactory != null) {
      authFactory.close();
    }
    if (tokenStore != null) {
      tokenStore.clear();
    }
  }

  @Test
  public void testMissingWebIdentityToken() {
    AssumeRoleWithWebIdentityRequest request =
        new AssumeRoleWithWebIdentityRequest();
    request.setRoleArn("arn:ozone:iam::role/test-role");
    request.setRoleSessionName("test-session");
    // Missing WebIdentityToken

    AssumeRoleWithWebIdentityAction action =
        new AssumeRoleWithWebIdentityAction(request);
    action.initialize(config, authFactory, credentialGenerator,
        tokenStore, jwtGenerator);

    Response response = action.execute();

    assertNotNull(response);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
        response.getStatus());
  }

  @Test
  public void testMissingRoleArn() {
    AssumeRoleWithWebIdentityRequest request =
        new AssumeRoleWithWebIdentityRequest();
    request.setWebIdentityToken("test-token");
    request.setRoleSessionName("test-session");
    // Missing RoleArn

    AssumeRoleWithWebIdentityAction action =
        new AssumeRoleWithWebIdentityAction(request);
    action.initialize(config, authFactory, credentialGenerator,
        tokenStore, jwtGenerator);

    Response response = action.execute();

    assertNotNull(response);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
        response.getStatus());
  }

  @Test
  public void testMissingRoleSessionName() {
    AssumeRoleWithWebIdentityRequest request =
        new AssumeRoleWithWebIdentityRequest();
    request.setWebIdentityToken("test-token");
    request.setRoleArn("arn:ozone:iam::role/test-role");
    // Missing RoleSessionName

    AssumeRoleWithWebIdentityAction action =
        new AssumeRoleWithWebIdentityAction(request);
    action.initialize(config, authFactory, credentialGenerator,
        tokenStore, jwtGenerator);

    Response response = action.execute();

    assertNotNull(response);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
        response.getStatus());
  }

  @Test
  public void testActionName() {
    AssumeRoleWithWebIdentityRequest request =
        new AssumeRoleWithWebIdentityRequest();
    AssumeRoleWithWebIdentityAction action =
        new AssumeRoleWithWebIdentityAction(request);

    assertEquals("AssumeRoleWithWebIdentity", action.getActionName());
  }
}

