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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating and managing authentication providers.
 */
public class AuthenticationProviderFactory {

  private static final Logger LOG =
      LoggerFactory.getLogger(AuthenticationProviderFactory.class);

  private final OzoneConfiguration config;
  private final Map<AuthenticationProvider.ProviderType,
      AuthenticationProvider> providers;
  private final List<AuthenticationProvider> orderedProviders;

  public AuthenticationProviderFactory(OzoneConfiguration config)
      throws IOException {
    this.config = config;
    this.providers = new HashMap<>();
    this.orderedProviders = new ArrayList<>();
    initializeProviders();
  }

  private void initializeProviders() throws IOException {
    String providersList = config.get(
        STSConfigKeys.OZONE_STS_AUTH_PROVIDERS,
        STSConfigKeys.OZONE_STS_AUTH_PROVIDERS_DEFAULT);

    if (providersList == null || providersList.trim().isEmpty()) {
      LOG.info("No authentication providers configured");
      return;
    }

    String[] providerTypes = providersList.split(",");
    for (String providerType : providerTypes) {
      providerType = providerType.trim().toUpperCase();
      if (providerType.isEmpty()) {
        continue;
      }

      try {
        AuthenticationProvider provider = createProvider(providerType);
        if (provider != null) {
          provider.initialize();
          if (provider.isEnabled()) {
            providers.put(provider.getProviderType(), provider);
            orderedProviders.add(provider);
            LOG.info("Initialized authentication provider: {}", providerType);
          } else {
            LOG.debug("Provider {} is not enabled, skipping", providerType);
          }
        }
      } catch (Exception e) {
        LOG.error("Failed to initialize provider: {}", providerType, e);
        // Continue with other providers
      }
    }
  }

  private AuthenticationProvider createProvider(String providerType)
      throws IOException {
    AuthenticationProvider.ProviderType type;
    try {
      type = AuthenticationProvider.ProviderType.valueOf(providerType);
    } catch (IllegalArgumentException e) {
      LOG.warn("Unknown provider type: {}", providerType);
      return null;
    }

    switch (type) {
    case IDP:
      return new IDPAuthenticationProvider(config);
    case LDAP:
      return new LDAPAuthenticationProvider(config);
    case AD:
      return new ADAuthenticationProvider(config);
    case REST_API:
      return new RestAPIAuthenticationProvider(config);
    default:
      LOG.warn("Unsupported provider type: {}", providerType);
      return null;
    }
  }

  /**
   * Get a provider by type.
   *
   * @param type provider type
   * @return authentication provider or null if not found
   */
  public AuthenticationProvider getProvider(
      AuthenticationProvider.ProviderType type) {
    return providers.get(type);
  }

  /**
   * Get all configured providers.
   *
   * @return list of providers
   */
  public List<AuthenticationProvider> getAllProviders() {
    return new ArrayList<>(orderedProviders);
  }

  /**
   * Authenticate using the first supporting provider.
   *
   * @param request authentication request
   * @param authenticationType authentication type
   * @return authentication result
   */
  public AuthenticationResult authenticate(AuthenticationRequest request,
      String authenticationType) {
    for (AuthenticationProvider provider : orderedProviders) {
      if (provider.supports(authenticationType)) {
        try {
          LOG.debug("Attempting authentication with provider: {}",
              provider.getProviderType());
          AuthenticationResult result = provider.authenticate(request);
          if (result.isSuccess()) {
            LOG.debug("Authentication successful with provider: {}",
                provider.getProviderType());
            return result;
          }
        } catch (Exception e) {
          LOG.warn("Authentication failed with provider: {}",
              provider.getProviderType(), e);
        }
      }
    }
    
    return AuthenticationResult.failure(
        "No provider could authenticate the request");
  }

  /**
   * Try authentication with all providers until one succeeds.
   *
   * @param request authentication request
   * @return authentication result
   */
  public AuthenticationResult authenticateAny(AuthenticationRequest request) {
    for (AuthenticationProvider provider : orderedProviders) {
      try {
        LOG.debug("Attempting authentication with provider: {}",
            provider.getProviderType());
        AuthenticationResult result = provider.authenticate(request);
        if (result.isSuccess()) {
          LOG.debug("Authentication successful with provider: {}",
              provider.getProviderType());
          return result;
        }
      } catch (Exception e) {
        LOG.debug("Authentication failed with provider: {}",
            provider.getProviderType(), e);
      }
    }
    
    return AuthenticationResult.failure(
        "Authentication failed with all providers");
  }

  /**
   * Close all providers.
   */
  public void close() {
    for (AuthenticationProvider provider : orderedProviders) {
      try {
        provider.close();
      } catch (Exception e) {
        LOG.warn("Error closing provider: {}", provider.getProviderType(), e);
      }
    }
    providers.clear();
    orderedProviders.clear();
  }
}

