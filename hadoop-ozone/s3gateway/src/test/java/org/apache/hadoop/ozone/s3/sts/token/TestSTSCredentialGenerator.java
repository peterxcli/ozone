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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for STSCredentialGenerator.
 */
public class TestSTSCredentialGenerator {

  private STSCredentialGenerator generator;
  private TokenStore tokenStore;
  private JWTTokenGenerator jwtGenerator;
  private OzoneConfiguration config;

  @BeforeEach
  public void setup() {
    config = new OzoneConfiguration();
    tokenStore = new TokenStore(config);
    jwtGenerator = new JWTTokenGenerator(config);
    generator = new STSCredentialGenerator(config, tokenStore, jwtGenerator);
  }

  @Test
  public void testGenerateCredentials() {
    String principal = "testuser@example.com";
    String roleArn = "arn:ozone:iam::role/test-role";
    String sessionName = "test-session";

    TemporaryCredentials credentials = generator.generateCredentials(
        principal, roleArn, sessionName, 3600L, null);

    assertNotNull(credentials);
    assertEquals(principal, credentials.getPrincipal());
    assertEquals(roleArn, credentials.getRoleArn());
    assertEquals(sessionName, credentials.getSessionName());
    assertTrue(credentials.getAccessKeyId().startsWith("STS-"));
    assertNotNull(credentials.getSecretAccessKey());
    assertNotNull(credentials.getSessionToken());
    assertFalse(credentials.isExpired());
  }

  @Test
  public void testGenerateCredentialsWithDefaultDuration() {
    String principal = "testuser@example.com";

    TemporaryCredentials credentials = generator.generateCredentials(
        principal, null, null, null, null);

    assertNotNull(credentials);
    
    long defaultDuration = config.getLong(
        STSConfigKeys.OZONE_STS_TOKEN_DEFAULT_DURATION,
        STSConfigKeys.OZONE_STS_TOKEN_DEFAULT_DURATION_DEFAULT);
    
    long actualDuration = credentials.getExpiration().toEpochMilli() -
        Instant.now().toEpochMilli();
    
    // Allow 1 second tolerance
    assertTrue(Math.abs(actualDuration - defaultDuration) < 1000);
  }

  @Test
  public void testGenerateCredentialsWithAttributes() {
    String principal = "testuser@example.com";
    Map<String, String> attributes = new HashMap<>();
    attributes.put("email", "testuser@example.com");
    attributes.put("department", "engineering");

    TemporaryCredentials credentials = generator.generateCredentials(
        principal, null, null, 3600L, attributes);

    assertNotNull(credentials);
    assertEquals("testuser@example.com", credentials.getAttribute("email"));
    assertEquals("engineering", credentials.getAttribute("department"));
  }

  @Test
  public void testDurationValidation() {
    long minDuration = generator.getMinDurationSeconds();
    long maxDuration = generator.getMaxLifetimeSeconds();
    long defaultDuration = generator.getDefaultDurationSeconds();

    assertTrue(minDuration > 0);
    assertTrue(maxDuration > minDuration);
    assertTrue(defaultDuration >= minDuration);
    assertTrue(defaultDuration <= maxDuration);
  }

  @Test
  public void testValidateDuration() {
    long minDuration = config.getLong(
        STSConfigKeys.OZONE_STS_TOKEN_MIN_DURATION,
        STSConfigKeys.OZONE_STS_TOKEN_MIN_DURATION_DEFAULT);
    long maxLifetime = config.getLong(
        STSConfigKeys.OZONE_STS_TOKEN_MAX_LIFETIME,
        STSConfigKeys.OZONE_STS_TOKEN_MAX_LIFETIME_DEFAULT);

    // Test too short duration - should use min
    long validated = generator.validateDuration(1L);
    assertEquals(minDuration, validated);

    // Test too long duration - should use max
    long tooLong = (maxLifetime / 1000) + 10000;
    validated = generator.validateDuration(tooLong);
    assertEquals(maxLifetime, validated);

    // Test normal duration
    validated = generator.validateDuration(3600L);
    assertEquals(3600000L, validated);
  }

  @Test
  public void testCredentialsStoredInTokenStore() {
    String principal = "testuser@example.com";

    TemporaryCredentials credentials = generator.generateCredentials(
        principal, null, null, 3600L, null);

    // Verify credentials are stored
    assertTrue(tokenStore.getCredentialsByAccessKey(
        credentials.getAccessKeyId()).isPresent());
    assertTrue(tokenStore.getCredentialsBySessionToken(
        credentials.getSessionToken()).isPresent());
  }

  @Test
  public void testUniqueAccessKeyIds() {
    TemporaryCredentials cred1 = generator.generateCredentials(
        "user1", null, null, 3600L, null);
    TemporaryCredentials cred2 = generator.generateCredentials(
        "user2", null, null, 3600L, null);

    assertNotNull(cred1.getAccessKeyId());
    assertNotNull(cred2.getAccessKeyId());
    assertFalse(cred1.getAccessKeyId().equals(cred2.getAccessKeyId()));
  }
}

