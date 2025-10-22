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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for JWTTokenGenerator.
 */
public class TestJWTTokenGenerator {

  private JWTTokenGenerator generator;
  private OzoneConfiguration config;

  @BeforeEach
  public void setup() {
    config = new OzoneConfiguration();
    config.set(STSConfigKeys.OZONE_STS_JWT_ISSUER, "test-issuer");
    config.set(STSConfigKeys.OZONE_STS_JWT_AUDIENCE, "test-audience");
    config.set(STSConfigKeys.OZONE_STS_JWT_SECRET, "test-secret-key-12345");
    
    generator = new JWTTokenGenerator(config);
  }

  @Test
  public void testGenerateToken() {
    String subject = "testuser@example.com";
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    String role = "arn:ozone:iam::role/test-role";
    String sessionName = "test-session";
    
    Map<String, String> customClaims = new HashMap<>();
    customClaims.put("email", "testuser@example.com");
    customClaims.put("department", "engineering");

    String token = generator.generateToken(
        subject, expiration, role, sessionName, customClaims);

    assertNotNull(token);
    assertFalse(token.isEmpty());
    
    // Verify token has three parts (header.payload.signature)
    String[] parts = token.split("\\.");
    assertEquals(3, parts.length);
  }

  @Test
  public void testParseToken() {
    String subject = "testuser@example.com";
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    String role = "arn:ozone:iam::role/test-role";
    String sessionName = "test-session";
    
    Map<String, String> customClaims = new HashMap<>();
    customClaims.put("email", "testuser@example.com");

    String token = generator.generateToken(
        subject, expiration, role, sessionName, customClaims);

    Claims claims = generator.parseToken(token);

    assertNotNull(claims);
    assertEquals(subject, claims.getSubject());
    assertEquals("test-issuer", claims.getIssuer());
    assertEquals("test-audience", claims.getAudience());
    assertEquals(role, claims.get("role"));
    assertEquals(sessionName, claims.get("sessionName"));
    assertEquals("testuser@example.com", claims.get("email"));
  }

  @Test
  public void testValidateToken() {
    String subject = "testuser@example.com";
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    
    String token = generator.generateToken(
        subject, expiration, null, null, null);

    assertTrue(generator.validateToken(token));
  }

  @Test
  public void testValidateExpiredToken() {
    String subject = "testuser@example.com";
    Instant expiration = Instant.now().minus(1, ChronoUnit.HOURS);
    
    String token = generator.generateToken(
        subject, expiration, null, null, null);

    assertFalse(generator.validateToken(token));
  }

  @Test
  public void testExtractSubject() {
    String subject = "testuser@example.com";
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    
    String token = generator.generateToken(
        subject, expiration, null, null, null);

    String extractedSubject = generator.extractSubject(token);
    assertEquals(subject, extractedSubject);
  }

  @Test
  public void testExtractExpiration() {
    String subject = "testuser@example.com";
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    
    String token = generator.generateToken(
        subject, expiration, null, null, null);

    Instant extractedExpiration = generator.extractExpiration(token);
    assertNotNull(extractedExpiration);
    
    // Allow 1 second tolerance for timing differences
    long diff = Math.abs(
        expiration.getEpochSecond() - extractedExpiration.getEpochSecond());
    assertTrue(diff <= 1);
  }

  @Test
  public void testInvalidToken() {
    String invalidToken = "invalid.token.here";
    
    assertFalse(generator.validateToken(invalidToken));
    assertNull(generator.extractSubject(invalidToken));
    assertNull(generator.extractExpiration(invalidToken));
  }
}

