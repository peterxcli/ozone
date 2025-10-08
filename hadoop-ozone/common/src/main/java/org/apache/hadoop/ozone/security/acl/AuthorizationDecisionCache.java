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

package org.apache.hadoop.ozone.security.acl;

import java.util.Optional;
import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.hdds.annotation.InterfaceStability;

/**
 * Interface for caching authorization decisions to improve performance.
 * Especially useful for external authorization systems like SpiceDB/OPA
 * where network latency can impact request latency.
 *
 * <p>Implementations should consider:
 * - Short TTLs (1-5 seconds) for positive decisions
 * - Longer TTLs or no caching for negative decisions
 * - Cache invalidation on policy updates
 * - Memory limits and eviction policies
 *
 * <p>Cache keys should include:
 * - Subject (user/service account)
 * - Resource (volume/bucket/key path)
 * - Action (READ/WRITE/DELETE/etc)
 * - Tenant (if multi-tenant)
 * - Session token hash (if applicable)
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "Yarn", "Ranger", "Hive", "HBase"})
@InterfaceStability.Evolving
public interface AuthorizationDecisionCache {

  /**
   * Get a cached authorization decision.
   *
   * @param key cache key representing the authorization query
   * @return cached decision (true=allow, false=deny) or empty if not cached
   */
  Optional<Boolean> get(CacheKey key);

  /**
   * Cache an authorization decision.
   *
   * @param key cache key
   * @param decision authorization decision (true=allow, false=deny)
   * @param ttlMillis time-to-live in milliseconds
   */
  void put(CacheKey key, boolean decision, long ttlMillis);

  /**
   * Invalidate all cached decisions for a specific resource.
   * Called when ACLs/policies are updated.
   *
   * @param resourcePath resource path (e.g., "/vol1/bucket1/key1")
   */
  void invalidateResource(String resourcePath);

  /**
   * Invalidate all cached decisions for a specific subject.
   * Called when user roles/groups are updated.
   *
   * @param subject subject identifier (e.g., "user:alice", "serviceaccount:spark")
   */
  void invalidateSubject(String subject);

  /**
   * Invalidate all cached decisions for a specific tenant.
   * Called when tenant policies are updated.
   *
   * @param tenantId tenant identifier
   */
  void invalidateTenant(String tenantId);

  /**
   * Clear all cached decisions.
   */
  void clear();

  /**
   * Get cache statistics for monitoring.
   *
   * @return cache stats
   */
  CacheStats getStats();

  /**
   * Cache key for authorization decisions.
   * Includes all relevant context for a unique authorization query.
   */
  class CacheKey {
    private final String subject;
    private final String resourcePath;
    private final String action;
    private final String tenantId;
    private final String sessionTokenHash;

    private CacheKey(Builder builder) {
      this.subject = builder.subject;
      this.resourcePath = builder.resourcePath;
      this.action = builder.action;
      this.tenantId = builder.tenantId;
      this.sessionTokenHash = builder.sessionTokenHash;
    }

    public String getSubject() {
      return subject;
    }

    public String getResourcePath() {
      return resourcePath;
    }

    public String getAction() {
      return action;
    }

    public Optional<String> getTenantId() {
      return Optional.ofNullable(tenantId);
    }

    public Optional<String> getSessionTokenHash() {
      return Optional.ofNullable(sessionTokenHash);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CacheKey cacheKey = (CacheKey) o;
      return java.util.Objects.equals(subject, cacheKey.subject) &&
          java.util.Objects.equals(resourcePath, cacheKey.resourcePath) &&
          java.util.Objects.equals(action, cacheKey.action) &&
          java.util.Objects.equals(tenantId, cacheKey.tenantId) &&
          java.util.Objects.equals(sessionTokenHash, cacheKey.sessionTokenHash);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(subject, resourcePath, action, tenantId,
          sessionTokenHash);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("CacheKey{");
      sb.append("subject='").append(subject).append('\'');
      sb.append(", resource='").append(resourcePath).append('\'');
      sb.append(", action='").append(action).append('\'');
      if (tenantId != null) {
        sb.append(", tenant='").append(tenantId).append('\'');
      }
      if (sessionTokenHash != null) {
        sb.append(", sessionHash='").append(sessionTokenHash).append('\'');
      }
      sb.append('}');
      return sb.toString();
    }

    public static Builder builder() {
      return new Builder();
    }

    /**
     * Builder for CacheKey.
     */
    public static class Builder {
      private String subject;
      private String resourcePath;
      private String action;
      private String tenantId;
      private String sessionTokenHash;

      public Builder subject(String subjectName) {
        this.subject = subjectName;
        return this;
      }

      public Builder resourcePath(String resource) {
        this.resourcePath = resource;
        return this;
      }

      public Builder action(String actionType) {
        this.action = actionType;
        return this;
      }

      public Builder tenantId(String tenant) {
        this.tenantId = tenant;
        return this;
      }

      public Builder sessionTokenHash(String tokenHash) {
        this.sessionTokenHash = tokenHash;
        return this;
      }

      public CacheKey build() {
        if (subject == null || resourcePath == null || action == null) {
          throw new IllegalArgumentException(
              "subject, resourcePath, and action are required");
        }
        return new CacheKey(this);
      }
    }
  }

  /**
   * Cache statistics for monitoring.
   */
  class CacheStats {
    private final long hits;
    private final long misses;
    private final long evictions;
    private final long size;

    public CacheStats(long hits, long misses, long evictions, long size) {
      this.hits = hits;
      this.misses = misses;
      this.evictions = evictions;
      this.size = size;
    }

    public long getHits() {
      return hits;
    }

    public long getMisses() {
      return misses;
    }

    public long getEvictions() {
      return evictions;
    }

    public long getSize() {
      return size;
    }

    public double getHitRate() {
      long total = hits + misses;
      return total == 0 ? 0.0 : (double) hits / total;
    }

    @Override
    public String toString() {
      return String.format("CacheStats{hits=%d, misses=%d, evictions=%d, " +
          "size=%d, hitRate=%.2f%%}", hits, misses, evictions, size,
          getHitRate() * 100);
    }
  }
}
