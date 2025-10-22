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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.codec.binary.Hex;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates temporary credentials for STS.
 */
public class STSCredentialGenerator {

  private static final Logger LOG =
      LoggerFactory.getLogger(STSCredentialGenerator.class);

  private static final String ACCESS_KEY_PREFIX = "STS-";
  private static final int SECRET_KEY_LENGTH = 40;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final long maxLifetime;
  private final long defaultDuration;
  private final long minDuration;
  private final TokenStore tokenStore;
  private final JWTTokenGenerator jwtGenerator;

  public STSCredentialGenerator(OzoneConfiguration config,
      TokenStore tokenStore, JWTTokenGenerator jwtGenerator) {
    this.tokenStore = tokenStore;
    this.jwtGenerator = jwtGenerator;

    this.maxLifetime = config.getLong(
        STSConfigKeys.OZONE_STS_TOKEN_MAX_LIFETIME,
        STSConfigKeys.OZONE_STS_TOKEN_MAX_LIFETIME_DEFAULT);
    this.defaultDuration = config.getLong(
        STSConfigKeys.OZONE_STS_TOKEN_DEFAULT_DURATION,
        STSConfigKeys.OZONE_STS_TOKEN_DEFAULT_DURATION_DEFAULT);
    this.minDuration = config.getLong(
        STSConfigKeys.OZONE_STS_TOKEN_MIN_DURATION,
        STSConfigKeys.OZONE_STS_TOKEN_MIN_DURATION_DEFAULT);
  }

  /**
   * Generate temporary credentials.
   *
   * @param principal user principal
   * @param roleArn role ARN (optional)
   * @param sessionName session name (optional)
   * @param durationSeconds requested duration in seconds (optional)
   * @param attributes additional attributes
   * @return temporary credentials
   */
  public TemporaryCredentials generateCredentials(String principal,
      String roleArn, String sessionName, Long durationSeconds,
      Map<String, String> attributes) {
    
    // Calculate expiration
    long durationMs;
    if (durationSeconds != null) {
      durationMs = durationSeconds * 1000;
      // Validate duration
      if (durationMs < minDuration) {
        durationMs = minDuration;
        LOG.debug("Duration too short, using minimum: {}ms", minDuration);
      } else if (durationMs > maxLifetime) {
        durationMs = maxLifetime;
        LOG.debug("Duration too long, using maximum: {}ms", maxLifetime);
      }
    } else {
      durationMs = defaultDuration;
    }

    Instant expiration = Instant.now().plusMillis(durationMs);

    // Generate access key ID
    String accessKeyId = generateAccessKeyId();

    // Generate secret access key
    String secretAccessKey = generateSecretKey();

    // Generate session token (JWT)
    String sessionToken = jwtGenerator.generateToken(
        principal,
        expiration,
        roleArn,
        sessionName,
        attributes
    );

    // Build credentials
    TemporaryCredentials.Builder credentialsBuilder =
        TemporaryCredentials.newBuilder()
            .setAccessKeyId(accessKeyId)
            .setSecretAccessKey(secretAccessKey)
            .setSessionToken(sessionToken)
            .setExpiration(expiration)
            .setPrincipal(principal);

    if (roleArn != null) {
      credentialsBuilder.setRoleArn(roleArn);
    }

    if (sessionName != null) {
      credentialsBuilder.setSessionName(sessionName);
    }

    if (attributes != null) {
      credentialsBuilder.setAttributes(attributes);
    }

    TemporaryCredentials credentials = credentialsBuilder.build();

    // Store credentials
    tokenStore.storeCredentials(credentials);

    LOG.info("Generated temporary credentials for principal: {}, " +
        "expires at: {}", principal, expiration);

    return credentials;
  }

  /**
   * Generate a unique access key ID.
   *
   * @return access key ID
   */
  private String generateAccessKeyId() {
    return ACCESS_KEY_PREFIX + UUID.randomUUID().toString()
        .replace("-", "").substring(0, 16).toUpperCase();
  }

  /**
   * Generate a secret access key.
   *
   * @return secret key
   */
  private String generateSecretKey() {
    byte[] bytes = new byte[SECRET_KEY_LENGTH / 2];
    RANDOM.nextBytes(bytes);
    return Hex.encodeHexString(bytes);
  }

  /**
   * Validate and normalize duration.
   *
   * @param durationSeconds requested duration
   * @return normalized duration in milliseconds
   */
  public long validateDuration(Long durationSeconds) {
    if (durationSeconds == null) {
      return defaultDuration;
    }

    long durationMs = durationSeconds * 1000;
    
    if (durationMs < minDuration) {
      return minDuration;
    } else if (durationMs > maxLifetime) {
      return maxLifetime;
    }
    
    return durationMs;
  }

  /**
   * Get default duration in seconds.
   *
   * @return default duration
   */
  public long getDefaultDurationSeconds() {
    return defaultDuration / 1000;
  }

  /**
   * Get maximum lifetime in seconds.
   *
   * @return max lifetime
   */
  public long getMaxLifetimeSeconds() {
    return maxLifetime / 1000;
  }

  /**
   * Get minimum duration in seconds.
   *
   * @return min duration
   */
  public long getMinDurationSeconds() {
    return minDuration / 1000;
  }
}

