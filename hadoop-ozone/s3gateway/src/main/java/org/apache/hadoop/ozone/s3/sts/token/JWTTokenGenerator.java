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

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates and validates JWT tokens for STS.
 */
public class JWTTokenGenerator {

  private static final Logger LOG =
      LoggerFactory.getLogger(JWTTokenGenerator.class);

  private final String issuer;
  private final String audience;
  private final Key signingKey;

  public JWTTokenGenerator(OzoneConfiguration config) {
    this.issuer = config.get(
        STSConfigKeys.OZONE_STS_JWT_ISSUER,
        STSConfigKeys.OZONE_STS_JWT_ISSUER_DEFAULT);
    this.audience = config.get(
        STSConfigKeys.OZONE_STS_JWT_AUDIENCE,
        STSConfigKeys.OZONE_STS_JWT_AUDIENCE_DEFAULT);
    String secret = config.get(
        STSConfigKeys.OZONE_STS_JWT_SECRET,
        STSConfigKeys.OZONE_STS_JWT_SECRET_DEFAULT);
    this.signingKey = generateKey(secret);
  }

  public JWTTokenGenerator(String issuer, String audience, String secret) {
    this.issuer = issuer;
    this.audience = audience;
    this.signingKey = generateKey(secret);
  }

  private Key generateKey(String secret) {
    byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length < 32) {
      // Ensure minimum key length for HS256
      return Keys.secretKeyFor(SignatureAlgorithm.HS256);
    }
    return Keys.hmacShaKeyFor(keyBytes);
  }

  /**
   * Generate a JWT token for the given credentials.
   *
   * @param credentials temporary credentials
   * @return JWT token string
   */
  public String generateToken(TemporaryCredentials credentials) {
    return generateToken(
        credentials.getPrincipal(),
        credentials.getExpiration(),
        credentials.getRoleArn(),
        credentials.getSessionName(),
        credentials.getAttributes()
    );
  }

  /**
   * Generate a JWT token with the given parameters.
   *
   * @param subject principal/subject
   * @param expiration expiration time
   * @param role role ARN
   * @param sessionName session name
   * @param customClaims additional custom claims
   * @return JWT token string
   */
  public String generateToken(String subject, Instant expiration,
      String role, String sessionName, Map<String, String> customClaims) {
    
    Instant now = Instant.now();
    
    io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
        .setSubject(subject)
        .setIssuer(issuer)
        .setAudience(audience)
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(expiration));

    if (role != null) {
      builder.claim("role", role);
    }
    if (sessionName != null) {
      builder.claim("sessionName", sessionName);
    }

    // Add custom claims
    if (customClaims != null && !customClaims.isEmpty()) {
      customClaims.forEach(builder::claim);
    }

    return builder
        .signWith(signingKey, SignatureAlgorithm.HS256)
        .compact();
  }

  /**
   * Parse and validate a JWT token.
   *
   * @param token JWT token string
   * @return Claims from the token
   * @throws io.jsonwebtoken.JwtException if token is invalid
   */
  public Claims parseToken(String token) {
    return Jwts.parserBuilder()
        .setSigningKey(signingKey)
        .requireIssuer(issuer)
        .requireAudience(audience)
        .build()
        .parseClaimsJws(token)
        .getBody();
  }

  /**
   * Validate a JWT token and check if it's expired.
   *
   * @param token JWT token string
   * @return true if token is valid and not expired
   */
  public boolean validateToken(String token) {
    try {
      Claims claims = parseToken(token);
      Date expiration = claims.getExpiration();
      return expiration != null && !expiration.before(new Date());
    } catch (Exception e) {
      LOG.debug("Token validation failed", e);
      return false;
    }
  }

  /**
   * Extract the subject (principal) from a JWT token.
   *
   * @param token JWT token string
   * @return subject/principal
   */
  public String extractSubject(String token) {
    try {
      return parseToken(token).getSubject();
    } catch (Exception e) {
      LOG.debug("Failed to extract subject from token", e);
      return null;
    }
  }

  /**
   * Extract expiration from a JWT token.
   *
   * @param token JWT token string
   * @return expiration instant
   */
  public Instant extractExpiration(String token) {
    try {
      Date expiration = parseToken(token).getExpiration();
      return expiration != null ? expiration.toInstant() : null;
    } catch (Exception e) {
      LOG.debug("Failed to extract expiration from token", e);
      return null;
    }
  }
}

