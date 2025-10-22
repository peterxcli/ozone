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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TokenStore.
 */
public class TestTokenStore {

  private TokenStore tokenStore;

  @BeforeEach
  public void setup() {
    OzoneConfiguration config = new OzoneConfiguration();
    tokenStore = new TokenStore(config);
  }

  @Test
  public void testStoreAndRetrieveCredentials() {
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-TEST123")
        .setSecretAccessKey("secret123")
        .setSessionToken("session123")
        .setPrincipal("testuser")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);

    Optional<TemporaryCredentials> retrieved =
        tokenStore.getCredentialsByAccessKey("STS-TEST123");
    
    assertTrue(retrieved.isPresent());
    assertEquals("testuser", retrieved.get().getPrincipal());
  }

  @Test
  public void testGetBySessionToken() {
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-TEST456")
        .setSecretAccessKey("secret456")
        .setSessionToken("session456")
        .setPrincipal("testuser2")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);

    Optional<TemporaryCredentials> retrieved =
        tokenStore.getCredentialsBySessionToken("session456");
    
    assertTrue(retrieved.isPresent());
    assertEquals("testuser2", retrieved.get().getPrincipal());
  }

  @Test
  public void testValidateSessionToken() {
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-TEST789")
        .setSecretAccessKey("secret789")
        .setSessionToken("session789")
        .setPrincipal("testuser3")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);

    assertTrue(tokenStore.validateSessionToken("STS-TEST789", "session789"));
    assertFalse(tokenStore.validateSessionToken("STS-TEST789", "wrong-token"));
    assertFalse(tokenStore.validateSessionToken("WRONG-KEY", "session789"));
  }

  @Test
  public void testExpiredCredentials() {
    Instant expiration = Instant.now().minus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-EXPIRED")
        .setSecretAccessKey("secret-expired")
        .setSessionToken("session-expired")
        .setPrincipal("expireduser")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);

    Optional<TemporaryCredentials> retrieved =
        tokenStore.getCredentialsByAccessKey("STS-EXPIRED");
    
    assertFalse(retrieved.isPresent());
  }

  @Test
  public void testRemoveCredentials() {
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-REMOVE")
        .setSecretAccessKey("secret-remove")
        .setSessionToken("session-remove")
        .setPrincipal("removeuser")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);
    assertTrue(tokenStore.getCredentialsByAccessKey("STS-REMOVE").isPresent());

    tokenStore.removeByAccessKey("STS-REMOVE");
    assertFalse(tokenStore.getCredentialsByAccessKey("STS-REMOVE").isPresent());
  }

  @Test
  public void testClear() {
    Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
    TemporaryCredentials credentials = TemporaryCredentials.newBuilder()
        .setAccessKeyId("STS-CLEAR")
        .setSecretAccessKey("secret-clear")
        .setSessionToken("session-clear")
        .setPrincipal("clearuser")
        .setExpiration(expiration)
        .build();

    tokenStore.storeCredentials(credentials);
    assertEquals(1, tokenStore.size());

    tokenStore.clear();
    assertEquals(0, tokenStore.size());
  }
}

