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

import java.io.Closeable;
import java.io.IOException;
import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.hdds.annotation.InterfaceStability;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.ozone.om.exceptions.OMException;

/**
 * Extended interface for pluggable Ozone authorization implementations.
 * This interface adds lifecycle management, health checks, and metrics
 * to support external authorization systems like SpiceDB, OPA, etc.
 *
 * <p>For simple authorizers that don't need lifecycle management,
 * use {@link IAccessAuthorizer} instead.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Constructor (with ConfigurationSource via reflection)</li>
 *   <li>{@link #start()} - Called once during initialization</li>
 *   <li>{@link #checkAccess(IOzoneObj, RequestContext)} - Called for each authz request</li>
 *   <li>{@link #stop()} - Called during shutdown</li>
 * </ol>
 *
 * @see IAccessAuthorizer
 * @see OzoneManagerAuthorizer
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "Yarn", "Ranger", "Hive", "HBase"})
@InterfaceStability.Evolving
public interface IAccessAuthorizerPlugin extends IAccessAuthorizer, Closeable {

  /**
   * Initialize and start the authorizer.
   * Called once during OzoneManager startup after construction.
   *
   * @param conf Configuration source
   * @throws IOException if initialization fails
   */
  void start(ConfigurationSource conf) throws IOException;

  /**
   * Stop the authorizer and release resources.
   * Called during OzoneManager shutdown.
   *
   * @throws IOException if shutdown fails
   */
  void stop() throws IOException;

  /**
   * Check if the authorizer is healthy and ready to serve requests.
   * This can be used for health checks and failover decisions.
   *
   * @return true if healthy, false otherwise
   */
  default boolean isHealthy() {
    return true;
  }

  /**
   * Get human-readable status information for debugging.
   *
   * @return status string (e.g., "Connected to SpiceDB at localhost:50051")
   */
  default String getStatus() {
    return "OK";
  }

  /**
   * Get metrics/statistics for monitoring.
   * Returns a simple key-value map of metric names to values.
   *
   * @return metrics map (e.g., {"total_checks": 12345, "cache_hits": 8901})
   */
  default java.util.Map<String, Long> getMetrics() {
    return java.util.Collections.emptyMap();
  }

  /**
   * Default implementation of Closeable.close() that delegates to stop().
   */
  @Override
  default void close() throws IOException {
    stop();
  }

  /**
   * Optional method to refresh/reload policies from external source.
   * Implementations can trigger policy refresh without full restart.
   *
   * @throws OMException if refresh fails
   */
  default void refresh() throws OMException {
    // no-op by default
  }

  /**
   * Returns the name of this authorizer implementation.
   * Used for logging and monitoring.
   *
   * @return authorizer name (e.g., "SpiceDB", "Ranger", "OPA")
   */
  default String getName() {
    return this.getClass().getSimpleName();
  }
}
