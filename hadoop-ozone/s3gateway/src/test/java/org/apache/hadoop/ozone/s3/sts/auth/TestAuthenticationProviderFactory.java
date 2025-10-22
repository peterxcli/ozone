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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AuthenticationProviderFactory.
 */
public class TestAuthenticationProviderFactory {

  @Test
  public void testFactoryInitialization() throws IOException {
    OzoneConfiguration config = new OzoneConfiguration();
    config.set(STSConfigKeys.OZONE_STS_AUTH_PROVIDERS, "IDP");
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_ISSUER,
        "https://accounts.google.com");

    AuthenticationProviderFactory factory =
        new AuthenticationProviderFactory(config);

    assertNotNull(factory);
    assertEquals(1, factory.getAllProviders().size());
    
    factory.close();
  }

  @Test
  public void testMultipleProviders() throws IOException {
    OzoneConfiguration config = new OzoneConfiguration();
    config.set(STSConfigKeys.OZONE_STS_AUTH_PROVIDERS, "IDP,LDAP");
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_ISSUER,
        "https://accounts.google.com");
    config.set(STSConfigKeys.OZONE_STS_LDAP_URL,
        "ldap://localhost:389");

    AuthenticationProviderFactory factory =
        new AuthenticationProviderFactory(config);

    assertEquals(2, factory.getAllProviders().size());
    assertNotNull(factory.getProvider(
        AuthenticationProvider.ProviderType.IDP));
    assertNotNull(factory.getProvider(
        AuthenticationProvider.ProviderType.LDAP));
    
    factory.close();
  }

  @Test
  public void testEmptyProvidersList() throws IOException {
    OzoneConfiguration config = new OzoneConfiguration();
    // No providers configured

    AuthenticationProviderFactory factory =
        new AuthenticationProviderFactory(config);

    assertEquals(0, factory.getAllProviders().size());
    
    factory.close();
  }

  @Test
  public void testAuthenticateAny() throws IOException {
    OzoneConfiguration config = new OzoneConfiguration();
    config.set(STSConfigKeys.OZONE_STS_AUTH_PROVIDERS, "IDP");
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_ISSUER,
        "https://accounts.google.com");

    AuthenticationProviderFactory factory =
        new AuthenticationProviderFactory(config);

    AuthenticationRequest request = AuthenticationRequest.newBuilder()
        .setUsername("testuser")
        .setPassword("testpass")
        .build();

    // This will fail since we don't have a valid OIDC token
    AuthenticationResult result = factory.authenticateAny(request);
    
    assertNotNull(result);
    assertNotNull(result.getErrorMessage());
    
    factory.close();
  }
}

