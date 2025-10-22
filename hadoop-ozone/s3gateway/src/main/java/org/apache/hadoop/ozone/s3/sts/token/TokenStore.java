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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages storage and lifecycle of temporary credentials.
 */
public class TokenStore {

  private static final Logger LOG =
      LoggerFactory.getLogger(TokenStore.class);

  private final Cache<String, TemporaryCredentials> accessKeyCache;
  private final Cache<String, TemporaryCredentials> sessionTokenCache;

  public TokenStore(OzoneConfiguration config) {
    int cacheSize = config.getInt(
        STSConfigKeys.OZONE_STS_TOKEN_STORE_CACHE_SIZE,
        STSConfigKeys.OZONE_STS_TOKEN_STORE_CACHE_SIZE_DEFAULT);
    
    long cacheExpiry = config.getLong(
        STSConfigKeys.OZONE_STS_TOKEN_STORE_CACHE_EXPIRY,
        STSConfigKeys.OZONE_STS_TOKEN_STORE_CACHE_EXPIRY_DEFAULT);

    this.accessKeyCache = CacheBuilder.newBuilder()
        .maximumSize(cacheSize)
        .expireAfterWrite(cacheExpiry, TimeUnit.MILLISECONDS)
        .build();

    this.sessionTokenCache = CacheBuilder.newBuilder()
        .maximumSize(cacheSize)
        .expireAfterWrite(cacheExpiry, TimeUnit.MILLISECONDS)
        .build();
  }

  /**
   * Store temporary credentials.
   *
   * @param credentials temporary credentials to store
   */
  public void storeCredentials(TemporaryCredentials credentials) {
    if (credentials == null) {
      throw new IllegalArgumentException("Credentials cannot be null");
    }

    LOG.debug("Storing credentials for access key: {}",
        credentials.getAccessKeyId());
    
    accessKeyCache.put(credentials.getAccessKeyId(), credentials);
    sessionTokenCache.put(credentials.getSessionToken(), credentials);
  }

  /**
   * Retrieve credentials by access key ID.
   *
   * @param accessKeyId access key ID
   * @return Optional containing credentials if found
   */
  public Optional<TemporaryCredentials> getCredentialsByAccessKey(
      String accessKeyId) {
    if (accessKeyId == null) {
      return Optional.empty();
    }

    TemporaryCredentials credentials = accessKeyCache.getIfPresent(accessKeyId);
    if (credentials != null && !credentials.isExpired()) {
      return Optional.of(credentials);
    } else if (credentials != null && credentials.isExpired()) {
      // Clean up expired credentials
      removeCredentials(credentials);
    }
    return Optional.empty();
  }

  /**
   * Retrieve credentials by session token.
   *
   * @param sessionToken session token
   * @return Optional containing credentials if found
   */
  public Optional<TemporaryCredentials> getCredentialsBySessionToken(
      String sessionToken) {
    if (sessionToken == null) {
      return Optional.empty();
    }

    TemporaryCredentials credentials =
        sessionTokenCache.getIfPresent(sessionToken);
    if (credentials != null && !credentials.isExpired()) {
      return Optional.of(credentials);
    } else if (credentials != null && credentials.isExpired()) {
      // Clean up expired credentials
      removeCredentials(credentials);
    }
    return Optional.empty();
  }

  /**
   * Validate session token.
   *
   * @param accessKeyId access key ID
   * @param sessionToken session token
   * @return true if valid and not expired
   */
  public boolean validateSessionToken(String accessKeyId, String sessionToken) {
    if (accessKeyId == null || sessionToken == null) {
      return false;
    }

    Optional<TemporaryCredentials> credentials =
        getCredentialsByAccessKey(accessKeyId);
    
    return credentials.isPresent() &&
        credentials.get().getSessionToken().equals(sessionToken) &&
        !credentials.get().isExpired();
  }

  /**
   * Remove credentials from store.
   *
   * @param credentials credentials to remove
   */
  public void removeCredentials(TemporaryCredentials credentials) {
    if (credentials == null) {
      return;
    }

    LOG.debug("Removing credentials for access key: {}",
        credentials.getAccessKeyId());
    
    accessKeyCache.invalidate(credentials.getAccessKeyId());
    sessionTokenCache.invalidate(credentials.getSessionToken());
  }

  /**
   * Remove credentials by access key ID.
   *
   * @param accessKeyId access key ID
   */
  public void removeByAccessKey(String accessKeyId) {
    Optional<TemporaryCredentials> credentials =
        getCredentialsByAccessKey(accessKeyId);
    credentials.ifPresent(this::removeCredentials);
  }

  /**
   * Remove credentials by session token.
   *
   * @param sessionToken session token
   */
  public void removeBySessionToken(String sessionToken) {
    Optional<TemporaryCredentials> credentials =
        getCredentialsBySessionToken(sessionToken);
    credentials.ifPresent(this::removeCredentials);
  }

  /**
   * Clear all stored credentials.
   */
  public void clear() {
    LOG.info("Clearing token store");
    accessKeyCache.invalidateAll();
    sessionTokenCache.invalidateAll();
  }

  /**
   * Get current size of the access key cache.
   *
   * @return cache size
   */
  public long size() {
    return accessKeyCache.size();
  }

  /**
   * Clean up expired credentials.
   */
  public void cleanupExpired() {
    LOG.debug("Cleaning up expired credentials");
    accessKeyCache.cleanUp();
    sessionTokenCache.cleanUp();
  }
}

