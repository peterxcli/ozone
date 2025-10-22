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

package org.apache.hadoop.ozone.s3.sts.token;

import java.time.Instant;
import java.util.Optional;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates temporary credentials and session tokens.
 */
public class TokenValidator {

  private static final Logger LOG =
      LoggerFactory.getLogger(TokenValidator.class);

  private final TokenStore tokenStore;
  private final JWTTokenGenerator jwtTokenGenerator;

  public TokenValidator(OzoneConfiguration config, TokenStore tokenStore) {
    this.tokenStore = tokenStore;
    this.jwtTokenGenerator = new JWTTokenGenerator(config);
  }

  public TokenValidator(TokenStore tokenStore,
      JWTTokenGenerator jwtTokenGenerator) {
    this.tokenStore = tokenStore;
    this.jwtTokenGenerator = jwtTokenGenerator;
  }

  /**
   * Validate temporary credentials.
   *
   * @param accessKeyId access key ID
   * @param sessionToken session token
   * @return ValidationResult with validation status and credentials
   */
  public ValidationResult validate(String accessKeyId, String sessionToken) {
    if (accessKeyId == null || sessionToken == null) {
      LOG.debug("Validation failed: null accessKeyId or sessionToken");
      return ValidationResult.invalid("Missing credentials");
    }

    // Check if access key is a temporary credential
    if (!isTemporaryAccessKey(accessKeyId)) {
      LOG.debug("Validation failed: not a temporary access key: {}",
          accessKeyId);
      return ValidationResult.invalid("Not a temporary access key");
    }

    // Retrieve credentials from store
    Optional<TemporaryCredentials> credentialsOpt =
        tokenStore.getCredentialsByAccessKey(accessKeyId);

    if (!credentialsOpt.isPresent()) {
      LOG.debug("Validation failed: credentials not found for: {}",
          accessKeyId);
      return ValidationResult.invalid("Credentials not found");
    }

    TemporaryCredentials credentials = credentialsOpt.get();

    // Verify session token matches
    if (!credentials.getSessionToken().equals(sessionToken)) {
      LOG.debug("Validation failed: session token mismatch for: {}",
          accessKeyId);
      return ValidationResult.invalid("Session token mismatch");
    }

    // Check expiration
    if (credentials.isExpired()) {
      LOG.debug("Validation failed: credentials expired for: {}",
          accessKeyId);
      tokenStore.removeCredentials(credentials);
      return ValidationResult.invalid("Credentials expired");
    }

    LOG.debug("Validation successful for: {}", accessKeyId);
    return ValidationResult.valid(credentials);
  }

  /**
   * Validate a JWT token.
   *
   * @param jwtToken JWT token string
   * @return ValidationResult with validation status
   */
  public ValidationResult validateJWT(String jwtToken) {
    if (jwtToken == null) {
      return ValidationResult.invalid("JWT token is null");
    }

    try {
      if (!jwtTokenGenerator.validateToken(jwtToken)) {
        return ValidationResult.invalid("JWT token is invalid or expired");
      }

      String subject = jwtTokenGenerator.extractSubject(jwtToken);
      Instant expiration = jwtTokenGenerator.extractExpiration(jwtToken);

      // For JWT validation, we create a minimal credential representation
      TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
          .setAccessKeyId("JWT")
          .setSecretAccessKey("")
          .setSessionToken(jwtToken)
          .setPrincipal(subject)
          .setExpiration(expiration != null ? expiration : Instant.now())
          .build();

      return ValidationResult.valid(credentials);
    } catch (Exception e) {
      LOG.debug("JWT validation failed", e);
      return ValidationResult.invalid("JWT validation failed: " +
          e.getMessage());
    }
  }

  /**
   * Check if an access key ID is a temporary credential.
   *
   * @param accessKeyId access key ID
   * @return true if temporary credential
   */
  public boolean isTemporaryAccessKey(String accessKeyId) {
    return accessKeyId != null && accessKeyId.startsWith("STS-");
  }

  /**
   * Extract principal from session token or JWT.
   *
   * @param sessionToken session token or JWT
   * @return principal/subject if found
   */
  public String extractPrincipal(String sessionToken) {
    if (sessionToken == null) {
      return null;
    }

    // Try as JWT token first
    try {
      String subject = jwtTokenGenerator.extractSubject(sessionToken);
      if (subject != null) {
        return subject;
      }
    } catch (Exception e) {
      LOG.debug("Not a JWT token, trying as session token", e);
    }

    // Try as session token
    Optional<TemporaryCredentials> credentials =
        tokenStore.getCredentialsBySessionToken(sessionToken);
    return credentials.map(TemporaryCredentials::getPrincipal).orElse(null);
  }

  /**
   * Result of token validation.
   */
  public static final class ValidationResult {
    private final boolean valid;
    private final String errorMessage;
    private final TemporaryCredentials credentials;

    private ValidationResult(boolean valid, String errorMessage,
        TemporaryCredentials credentials) {
      this.valid = valid;
      this.errorMessage = errorMessage;
      this.credentials = credentials;
    }

    public static ValidationResult valid(TemporaryCredentials credentials) {
      return new ValidationResult(true, null, credentials);
    }

    public static ValidationResult invalid(String errorMessage) {
      return new ValidationResult(false, errorMessage, null);
    }

    public boolean isValid() {
      return valid;
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    public Optional<TemporaryCredentials> getCredentials() {
      return Optional.ofNullable(credentials);
    }
  }
}

