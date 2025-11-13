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

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.metrics2.MetricsCollector;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import org.apache.hadoop.metrics2.lib.MutableRate;

/**
 * Metrics for Ozone authorization operations.
 * Tracks authorization checks, decisions, latencies, and errors.
 */
@InterfaceAudience.Private
@Metrics(about = "Ozone Authorizer Metrics", context = "ozone")
public class AuthorizerMetrics implements MetricsSource {

  public static final String SOURCE_NAME =
      AuthorizerMetrics.class.getSimpleName();

  // Authorization check metrics
  @Metric("Total number of authorization checks")
  private MutableCounterLong numAuthChecks;

  @Metric("Number of allowed authorization decisions")
  private MutableCounterLong numAllowed;

  @Metric("Number of denied authorization decisions")
  private MutableCounterLong numDenied;

  @Metric("Number of authorization errors")
  private MutableCounterLong numErrors;

  // Latency metrics
  @Metric("Authorization check latency")
  private MutableRate authCheckLatency;

  // Cache metrics
  @Metric("Number of cache hits")
  private MutableCounterLong numCacheHits;

  @Metric("Number of cache misses")
  private MutableCounterLong numCacheMisses;

  // Per-action metrics
  @Metric("Number of READ authorization checks")
  private MutableCounterLong numReadChecks;

  @Metric("Number of WRITE authorization checks")
  private MutableCounterLong numWriteChecks;

  @Metric("Number of DELETE authorization checks")
  private MutableCounterLong numDeleteChecks;

  @Metric("Number of LIST authorization checks")
  private MutableCounterLong numListChecks;

  // Per-resource metrics
  @Metric("Number of VOLUME authorization checks")
  private MutableCounterLong numVolumeChecks;

  @Metric("Number of BUCKET authorization checks")
  private MutableCounterLong numBucketChecks;

  @Metric("Number of KEY authorization checks")
  private MutableCounterLong numKeyChecks;

  /**
   * Create and register metrics.
   */
  public static AuthorizerMetrics create() {
    MetricsSystem ms = DefaultMetricsSystem.instance();
    return ms.register(SOURCE_NAME, "Ozone Authorizer Metrics",
        new AuthorizerMetrics());
  }

  /**
   * Unregister metrics.
   */
  public void unRegister() {
    MetricsSystem ms = DefaultMetricsSystem.instance();
    ms.unregisterSource(SOURCE_NAME);
  }

  /**
   * Record an authorization check.
   *
   * @param allowed whether access was allowed
   * @param latencyNanos latency in nanoseconds
   */
  public void recordAuthCheck(boolean allowed, long latencyNanos) {
    numAuthChecks.incr();
    if (allowed) {
      numAllowed.incr();
    } else {
      numDenied.incr();
    }
    authCheckLatency.add(latencyNanos / 1_000_000); // convert to ms
  }

  /**
   * Record an authorization error.
   */
  public void recordAuthError() {
    numErrors.incr();
  }

  /**
   * Record a cache hit.
   */
  public void recordCacheHit() {
    numCacheHits.incr();
  }

  /**
   * Record a cache miss.
   */
  public void recordCacheMiss() {
    numCacheMisses.incr();
  }

  /**
   * Record an authorization check by action type.
   *
   * @param action ACL action type
   */
  public void recordActionCheck(IAccessAuthorizer.ACLType action) {
    switch (action) {
    case READ:
    case READ_ACL:
      numReadChecks.incr();
      break;
    case WRITE:
    case WRITE_ACL:
      numWriteChecks.incr();
      break;
    case DELETE:
      numDeleteChecks.incr();
      break;
    case LIST:
      numListChecks.incr();
      break;
    default:
      // CREATE, ALL, NONE handled in total counts
      break;
    }
  }

  /**
   * Record an authorization check by resource type.
   *
   * @param resourceType resource type
   */
  public void recordResourceCheck(String resourceType) {
    switch (resourceType.toUpperCase()) {
    case "VOLUME":
      numVolumeChecks.incr();
      break;
    case "BUCKET":
      numBucketChecks.incr();
      break;
    case "KEY":
      numKeyChecks.incr();
      break;
    default:
      // Other types handled in total counts
      break;
    }
  }

  @VisibleForTesting
  public long getNumAuthChecks() {
    return numAuthChecks.value();
  }

  @VisibleForTesting
  public long getNumAllowed() {
    return numAllowed.value();
  }

  @VisibleForTesting
  public long getNumDenied() {
    return numDenied.value();
  }

  @VisibleForTesting
  public long getNumErrors() {
    return numErrors.value();
  }

  @VisibleForTesting
  public long getNumCacheHits() {
    return numCacheHits.value();
  }

  @VisibleForTesting
  public long getNumCacheMisses() {
    return numCacheMisses.value();
  }

  @VisibleForTesting
  public double getCacheHitRate() {
    long hits = numCacheHits.value();
    long misses = numCacheMisses.value();
    long total = hits + misses;
    return total == 0 ? 0.0 : (double) hits / total;
  }

  @Override
  public void getMetrics(MetricsCollector collector, boolean all) {
    MetricsRecordBuilder rb = collector.addRecord(SOURCE_NAME);
    numAuthChecks.snapshot(rb, all);
    numAllowed.snapshot(rb, all);
    numDenied.snapshot(rb, all);
    numErrors.snapshot(rb, all);
    authCheckLatency.snapshot(rb, all);
    numCacheHits.snapshot(rb, all);
    numCacheMisses.snapshot(rb, all);
    numReadChecks.snapshot(rb, all);
    numWriteChecks.snapshot(rb, all);
    numDeleteChecks.snapshot(rb, all);
    numListChecks.snapshot(rb, all);
    numVolumeChecks.snapshot(rb, all);
    numBucketChecks.snapshot(rb, all);
    numKeyChecks.snapshot(rb, all);
  }
}
