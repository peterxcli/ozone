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

package org.apache.hadoop.ozone.s3.sts.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Base64;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for IDPAuthenticationProvider.
 */
public class TestIDPAuthenticationProvider {

  private IDPAuthenticationProvider provider;
  private OzoneConfiguration config;

  @BeforeEach
  public void setup() throws IOException {
    config = new OzoneConfiguration();
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_ISSUER,
        "https://accounts.google.com");
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_CLIENT_ID, "test-client");

    provider = new IDPAuthenticationProvider(config);
    provider.initialize();
  }

  @Test
  public void testProviderType() {
    assertEquals(AuthenticationProvider.ProviderType.IDP,
        provider.getProviderType());
  }

  @Test
  public void testSupports() {
    assertTrue(provider.supports("OIDC"));
    assertTrue(provider.supports("SAML"));
    assertTrue(provider.supports("WEB_IDENTITY"));
    assertFalse(provider.supports("LDAP"));
  }

  @Test
  public void testIsEnabled() {
    assertTrue(provider.isEnabled());
  }

  @Test
  public void testAuthenticateWithoutToken() throws IOException {
    AuthenticationRequest request = AuthenticationRequest.newBuilder()
        .setUsername("testuser")
        .build();

    AuthenticationResult result = provider.authenticate(request);

    assertFalse(result.isSuccess());
    assertNotNull(result.getErrorMessage());
  }

  @Test
  public void testAuthenticateWithInvalidToken() throws IOException {
    // Create a simple invalid JWT token
    String invalidToken = "invalid.jwt.token";

    AuthenticationRequest request = AuthenticationRequest.newBuilder()
        .setWebIdentityToken(invalidToken)
        .build();

    AuthenticationResult result = provider.authenticate(request);

    assertFalse(result.isSuccess());
  }

  @Test
  public void testAuthenticateWithMalformedSAML() throws IOException {
    String invalidSaml = Base64.getEncoder().encodeToString(
        "<invalid>xml</invalid>".getBytes());

    AuthenticationRequest request = AuthenticationRequest.newBuilder()
        .setSamlAssertion(invalidSaml)
        .build();

    AuthenticationResult result = provider.authenticate(request);

    assertFalse(result.isSuccess());
  }
}

