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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation of {@link IAccessAuthorizerPlugin} that provides:
 * - Decision caching with configurable TTLs.
 * - Metrics collection
 * - Health check scaffolding
 * - Lifecycle management
 *
 * <p>Subclasses only need to implement:
 * - {@link #doStart(ConfigurationSource)} - initialization logic
 * - {@link #doCheckAccess(IOzoneObj, RequestContext)} - actual authz logic
 * - {@link #doStop()} - cleanup logic
 *
 * <p>Example:
 * <pre>
 * public class SpiceDBAuthorizer extends BaseAuthorizerPlugin {
 *   private SpiceDBClient client;
 *
 *   protected void doStart(ConfigurationSource configuration)
 *       throws IOException {
 *     String endpoint = configuration.get("ozone.authorizer.spicedb.endpoint");
 *     client = new SpiceDBClient(endpoint);
 *   }
 *
 *   protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
 *     return client.checkPermission(obj, ctx);
 *   }
 *
 *   protected void doStop() throws IOException {
 *     client.close();
 *   }
 * }
 * </pre>
 */
public abstract class BaseAuthorizerPlugin implements IAccessAuthorizerPlugin,
    AuthorizationDecisionCache {

  private static final Logger LOG = LoggerFactory.getLogger(
      BaseAuthorizerPlugin.class);

  // Configuration keys
  protected static final String CACHE_ENABLED_KEY =
      "ozone.authorizer.cache.enabled";
  protected static final boolean CACHE_ENABLED_DEFAULT = true;

  protected static final String CACHE_SIZE_KEY =
      "ozone.authorizer.cache.max.size";
  protected static final int CACHE_SIZE_DEFAULT = 10000;

  protected static final String CACHE_TTL_MS_KEY =
      "ozone.authorizer.cache.ttl.ms";
  protected static final long CACHE_TTL_MS_DEFAULT = 5000; // 5 seconds

  protected static final String METRICS_ENABLED_KEY =
      "ozone.authorizer.metrics.enabled";
  protected static final boolean METRICS_ENABLED_DEFAULT = true;

  // State
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean healthy = new AtomicBoolean(true);

  // Cache
  private Cache<CacheKey, CachedDecision> decisionCache;
  private boolean cacheEnabled;
  private long cacheTtlMs;

  // Metrics
  private AuthorizerMetrics metrics;
  private boolean metricsEnabled;
  private final AtomicLong cacheHits = new AtomicLong(0);
  private final AtomicLong cacheMisses = new AtomicLong(0);
  private final AtomicLong cacheEvictions = new AtomicLong(0);

  // Configuration
  private ConfigurationSource configuration;

  /**
   * Get the configuration source.
   * @return configuration source
   */
  protected ConfigurationSource getConfiguration() {
    return configuration;
  }

  @Override
  public final void start(ConfigurationSource conf) throws IOException {
    if (started.getAndSet(true)) {
      LOG.warn("{} already started, ignoring duplicate start() call",
          getName());
      return;
    }

    this.configuration = conf;

    // Initialize cache
    cacheEnabled = conf.getBoolean(CACHE_ENABLED_KEY, CACHE_ENABLED_DEFAULT);
    if (cacheEnabled) {
      int cacheSize = conf.getInt(CACHE_SIZE_KEY, CACHE_SIZE_DEFAULT);
      cacheTtlMs = conf.getLong(CACHE_TTL_MS_KEY, CACHE_TTL_MS_DEFAULT);
      // Use max TTL for cache expiration, but check per-entry TTL on retrieval
      decisionCache = CacheBuilder.newBuilder()
          .maximumSize(cacheSize)
          .expireAfterWrite(cacheTtlMs * 2, TimeUnit.MILLISECONDS)
          .recordStats()
          .removalListener(notification -> cacheEvictions.incrementAndGet())
          .build();
      LOG.info("{}: Decision cache enabled (size={}, ttl={}ms)",
          getName(), cacheSize, cacheTtlMs);
    }

    // Initialize metrics
    metricsEnabled = conf.getBoolean(METRICS_ENABLED_KEY,
        METRICS_ENABLED_DEFAULT);
    if (metricsEnabled) {
      metrics = AuthorizerMetrics.create();
      LOG.info("{}: Metrics enabled", getName());
    }

    // Subclass initialization
    try {
      doStart(conf);
      LOG.info("{}: Started successfully", getName());
    } catch (Exception e) {
      started.set(false);
      healthy.set(false);
      throw new IOException("Failed to start " + getName(), e);
    }
  }

  @Override
  public final void stop() throws IOException {
    if (!started.getAndSet(false)) {
      LOG.warn("{} not started, ignoring stop() call", getName());
      return;
    }

    try {
      doStop();
    } finally {
      if (decisionCache != null) {
        decisionCache.invalidateAll();
      }
      if (metrics != null) {
        metrics.unRegister();
      }
      LOG.info("{}: Stopped", getName());
    }
  }

  @Override
  public final boolean checkAccess(IOzoneObj ozoneObject,
      RequestContext context) throws OMException {
    if (!started.get()) {
      throw new OMException(getName() + " not started",
          OMException.ResultCodes.INTERNAL_ERROR);
    }

    long startNanos = System.nanoTime();
    boolean allowed = false;
    boolean cached = false;

    try {
      // Check cache first
      if (cacheEnabled) {
        CacheKey key = buildCacheKey(ozoneObject, context);
        CachedDecision cachedDecision = decisionCache.getIfPresent(key);
        if (cachedDecision != null && !cachedDecision.isExpired()) {
          allowed = cachedDecision.getDecision();
          cached = true;
          cacheHits.incrementAndGet();
          if (metricsEnabled) {
            metrics.recordCacheHit();
          }
          return allowed;
        } else if (cachedDecision != null && cachedDecision.isExpired()) {
          // Remove expired entry
          decisionCache.invalidate(key);
        }
        cacheMisses.incrementAndGet();
        if (metricsEnabled) {
          metrics.recordCacheMiss();
        }
      }

      // Perform actual authorization check
      allowed = doCheckAccess(ozoneObject, context);

      // Cache the decision
      if (cacheEnabled) {
        CacheKey key = buildCacheKey(ozoneObject, context);
        put(key, allowed, cacheTtlMs);
      }

      return allowed;

    } catch (Exception e) {
      healthy.set(false);
      if (metricsEnabled) {
        metrics.recordAuthError();
      }
      throw new OMException("Authorization check failed: " + e.getMessage(),
          e, OMException.ResultCodes.INTERNAL_ERROR);
    } finally {
      if (metricsEnabled && !cached) {
        long latencyNanos = System.nanoTime() - startNanos;
        metrics.recordAuthCheck(allowed, latencyNanos);
        metrics.recordActionCheck(context.getAclRights());
        if (ozoneObject instanceof OzoneObjInfo) {
          metrics.recordResourceCheck(
              ((OzoneObjInfo) ozoneObject).getResourceType().name());
        }
      }
    }
  }

  @Override
  public boolean isHealthy() {
    return started.get() && healthy.get();
  }

  @Override
  public String getStatus() {
    if (!started.get()) {
      return "Not started";
    }
    if (!healthy.get()) {
      return "Unhealthy";
    }
    return "Healthy";
  }

  @Override
  public Map<String, Long> getMetrics() {
    Map<String, Long> map = new HashMap<>();
    if (metricsEnabled && metrics != null) {
      map.put("total_checks", metrics.getNumAuthChecks());
      map.put("allowed", metrics.getNumAllowed());
      map.put("denied", metrics.getNumDenied());
      map.put("errors", metrics.getNumErrors());
    }
    if (cacheEnabled) {
      map.put("cache_hits", cacheHits.get());
      map.put("cache_misses", cacheMisses.get());
      map.put("cache_evictions", cacheEvictions.get());
      map.put("cache_size", decisionCache.size());
    }
    return map;
  }

  // AuthorizationDecisionCache implementation

  @Override
  public Optional<Boolean> get(CacheKey key) {
    if (!cacheEnabled || decisionCache == null) {
      return Optional.empty();
    }
    CachedDecision cached = decisionCache.getIfPresent(key);
    if (cached != null && !cached.isExpired()) {
      return Optional.of(cached.getDecision());
    } else if (cached != null && cached.isExpired()) {
      decisionCache.invalidate(key);
    }
    return Optional.empty();
  }

  @Override
  public void put(CacheKey key, boolean decision, long ttlMillis) {
    if (cacheEnabled && decisionCache != null) {
      long expirationTime = System.currentTimeMillis() + ttlMillis;
      decisionCache.put(key, new CachedDecision(decision, expirationTime));
    }
  }

  @Override
  public void invalidateResource(String resourcePath) {
    if (cacheEnabled && decisionCache != null) {
      // Normalize the resource path
      String normalizedPath = resourcePath.endsWith("/") 
          ? resourcePath : resourcePath + "/";
      
      decisionCache.asMap().keySet().removeIf(key -> {
        String keyPath = key.getResourcePath();
        // Exact match or hierarchical match (e.g., /vol1 matches /vol1/bucket1)
        return keyPath.equals(resourcePath) 
            || keyPath.startsWith(normalizedPath);
      });
    }
  }

  @Override
  public void invalidateSubject(String subject) {
    if (cacheEnabled && decisionCache != null) {
      decisionCache.asMap().keySet().removeIf(
          key -> key.getSubject().equals(subject));
    }
  }

  @Override
  public void invalidateTenant(String tenantId) {
    if (cacheEnabled && decisionCache != null) {
      decisionCache.asMap().keySet().removeIf(
          key -> key.getTenantId().map(t -> t.equals(tenantId)).orElse(false));
    }
  }

  @Override
  public void clear() {
    if (cacheEnabled && decisionCache != null) {
      decisionCache.invalidateAll();
    }
  }

  @Override
  public CacheStats getStats() {
    if (cacheEnabled && decisionCache != null) {
      return new CacheStats(
          cacheHits.get(),
          cacheMisses.get(),
          cacheEvictions.get(),
          decisionCache.size()
      );
    }
    return new CacheStats(0, 0, 0, 0);
  }

  /**
   * Build a cache key from the authorization context.
   * Can be overridden by subclasses for custom caching strategies.
   */
  protected CacheKey buildCacheKey(IOzoneObj obj, RequestContext context) {
    String resourcePath = buildResourcePath(obj);
    String subject = context.getClientUgi().getShortUserName();
    String action = context.getAclRights().name();

    return CacheKey.builder()
        .subject(subject)
        .resourcePath(resourcePath)
        .action(action)
        .build();
  }

  /**
   * Build a resource path string from an Ozone object.
   */
  protected String buildResourcePath(IOzoneObj obj) {
    if (obj instanceof OzoneObj) {
      return ((OzoneObj) obj).getPath();
    }
    if (obj instanceof OzoneObjInfo) {
      OzoneObjInfo info = (OzoneObjInfo) obj;
      StringBuilder sb = new StringBuilder();
      if (info.getVolumeName() != null) {
        sb.append("/").append(info.getVolumeName());
      }
      if (info.getBucketName() != null) {
        sb.append("/").append(info.getBucketName());
      }
      if (info.getKeyName() != null) {
        sb.append("/").append(info.getKeyName());
      }
      return sb.toString();
    }
    return obj.toString();
  }

  /**
   * Subclass-specific initialization.
   * Called once during start().
   *
   * @param conf configuration
   * @throws IOException if initialization fails
   */
  protected abstract void doStart(ConfigurationSource conf) throws IOException;

  /**
   * Perform the actual authorization check.
   * Called for each access check (cache misses only).
   *
   * @param ozoneObject resource being accessed
   * @param context request context with user, action, etc.
   * @return true if access is allowed, false otherwise
   * @throws OMException if authorization check fails
   */
  protected abstract boolean doCheckAccess(IOzoneObj ozoneObject,
      RequestContext context) throws OMException;

  /**
   * Subclass-specific cleanup.
   * Called once during stop().
   *
   * @throws IOException if cleanup fails
   */
  protected abstract void doStop() throws IOException;

  /**
   * Wrapper class to store cached authorization decisions with expiration time.
   * Allows per-entry TTL support even though Guava Cache uses a global TTL.
   */
  private static class CachedDecision {
    private final boolean decision;
    private final long expirationTimeMs;

    CachedDecision(boolean decision, long expirationTimeMs) {
      this.decision = decision;
      this.expirationTimeMs = expirationTimeMs;
    }

    boolean getDecision() {
      return decision;
    }

    boolean isExpired() {
      return System.currentTimeMillis() > expirationTimeMs;
    }
  }
}
