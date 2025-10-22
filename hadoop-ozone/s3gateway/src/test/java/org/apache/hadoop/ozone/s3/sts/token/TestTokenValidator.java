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
import java.time.temporal.ChronoUnit;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TokenValidator.
 */
public class TestTokenValidator {

  private TokenValidator validator;
  private TokenStore tokenStore;
  private JWTTokenGenerator jwtGenerator;

  @BeforeEach
  public void setup() {
    OzoneConfiguration config = new OzoneConfiguration();
    tokenStore = new TokenStore(config);
    jwtGenerator = new JWTTokenGenerator(config);
    validator = new TokenValidator(config, tokenStore);
  }

  @Test
  public void testValidateValidCredentials() {
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-VALID123")
        .setSecretAccessKey("secret123")
        .setSessionToken("session123")
        .setPrincipal("testuser")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);

    TokenValidator.ValidationResult result =
        validator.validate("STS-VALID123", "session123");

    assertTrue(result.isValid());
    assertTrue(result.getCredentials().isPresent());
    assertEquals("testuser", result.getCredentials().get().getPrincipal());
  }

  @Test
  public void testValidateInvalidAccessKey() {
    TokenValidator.ValidationResult result =
        validator.validate("REGULAR-KEY", "session123");

    assertFalse(result.isValid());
    assertNotNull(result.getErrorMessage());
  }

  @Test
  public void testValidateExpiredCredentials() {
    Instant expiration = Instant.now().minus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-EXPIRED123")
        .setSecretAccessKey("secret-expired")
        .setSessionToken("session-expired")
        .setPrincipal("expireduser")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);

    TokenValidator.ValidationResult result =
        validator.validate("STS-EXPIRED123", "session-expired");

    assertFalse(result.isValid());
  }

  @Test
  public void testValidateJWT() {
    String jwt = jwtGenerator.generateToken(
        "testuser",
        Instant.now().plus(1, ChronoUnit.HOURS),
        "arn:ozone:iam::role/test",
        "session1",
        null
    );

    TokenValidator.ValidationResult result = validator.validateJWT(jwt);

    assertTrue(result.isValid());
    assertTrue(result.getCredentials().isPresent());
    assertEquals("testuser", result.getCredentials().get().getPrincipal());
  }

  @Test
  public void testValidateExpiredJWT() {
    String jwt = jwtGenerator.generateToken(
        "testuser",
        Instant.now().minus(1, ChronoUnit.HOURS),
        null,
        null,
        null
    );

    TokenValidator.ValidationResult result = validator.validateJWT(jwt);

    assertFalse(result.isValid());
  }

  @Test
  public void testIsTemporaryAccessKey() {
    assertTrue(validator.isTemporaryAccessKey("STS-ABC123"));
    assertFalse(validator.isTemporaryAccessKey("REGULAR-KEY"));
    assertFalse(validator.isTemporaryAccessKey(null));
  }

  @Test
  public void testExtractPrincipal() {
    String jwt = jwtGenerator.generateToken(
        "principal@example.com",
        Instant.now().plus(1, ChronoUnit.HOURS),
        null,
        null,
        null
    );

    String principal = validator.extractPrincipal(jwt);
    assertEquals("principal@example.com", principal);
  }
}

