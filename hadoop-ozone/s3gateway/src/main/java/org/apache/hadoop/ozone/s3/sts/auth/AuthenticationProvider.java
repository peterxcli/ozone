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

/**
 * Interface for pluggable authentication providers.
 */
public interface AuthenticationProvider {

  /**
   * Authentication provider types.
   */
  enum ProviderType {
    IDP,
    LDAP,
    AD,
    REST_API,
    KERBEROS
  }

  /**
   * Authenticate a request.
   *
   * @param request authentication request
   * @return authentication result
   * @throws IOException if authentication fails
   */
  AuthenticationResult authenticate(AuthenticationRequest request)
      throws IOException;

  /**
   * Get the provider type.
   *
   * @return provider type
   */
  ProviderType getProviderType();

  /**
   * Check if this provider supports the given authentication type.
   *
   * @param authenticationType authentication type (e.g., "SAML", "OIDC", etc.)
   * @return true if supported
   */
  boolean supports(String authenticationType);

  /**
   * Initialize the provider with configuration.
   *
   * @throws IOException if initialization fails
   */
  void initialize() throws IOException;

  /**
   * Cleanup and close resources.
   */
  void close();

  /**
   * Check if the provider is enabled.
   *
   * @return true if enabled
   */
  boolean isEnabled();
}

