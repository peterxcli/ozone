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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.hdds.annotation.InterfaceStability;

/**
 * Extended authorization context that supports modern IAM features:
 * - OIDC/OAuth2 tokens and claims
 * - Multi-tenancy information
 * - Session tokens and temporary credentials
 * - Custom attributes and metadata
 *
 * <p>This class wraps the legacy {@link RequestContext} and adds
 * extensible key-value attributes for modern authorization systems.
 *
 * <p>Example usage for OIDC-based authorization:
 * <pre>
 * OzoneAuthorizationContext ctx = OzoneAuthorizationContext.builder()
 *     .requestContext(legacyContext)
 *     .tenantId("acme")
 *     .sessionToken("eyJhbGci...")
 *     .attribute("oidc_subject", "user@example.com")
 *     .attribute("oidc_groups", Arrays.asList("admin", "developers"))
 *     .build();
 * </pre>
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "Yarn", "Ranger", "Hive", "HBase"})
@InterfaceStability.Evolving
public final class OzoneAuthorizationContext {

  private final RequestContext requestContext;
  private final String tenantId;
  private final String sessionToken;
  private final Map<String, Object> attributes;

  private OzoneAuthorizationContext(Builder builder) {
    this.requestContext = builder.requestContext;
    this.tenantId = builder.tenantId;
    this.sessionToken = builder.sessionToken;
    this.attributes = Collections.unmodifiableMap(
        new HashMap<>(builder.attributes));
  }

  /**
   * Get the legacy request context.
   * @return request context with UGI, IP, ACL rights, etc.
   */
  public RequestContext getRequestContext() {
    return requestContext;
  }

  /**
   * Get the tenant ID for multi-tenant authorization.
   * @return tenant ID (e.g., "acme", "beta") or empty if not multi-tenant
   */
  public Optional<String> getTenantId() {
    return Optional.ofNullable(tenantId);
  }

  /**
   * Get the session token (e.g., AWS STS session token, OIDC ID token).
   * @return session token or empty if not present
   */
  public Optional<String> getSessionToken() {
    return Optional.ofNullable(sessionToken);
  }

  /**
   * Get a custom attribute by key.
   * Useful for passing OIDC claims, roles, or other metadata.
   *
   * @param key attribute key (e.g., "oidc_subject", "roles")
   * @return attribute value or empty if not present
   */
  public Optional<Object> getAttribute(String key) {
    return Optional.ofNullable(attributes.get(key));
  }

  /**
   * Get a typed attribute by key.
   *
   * @param key attribute key
   * @param type expected type
   * @param <T> attribute type
   * @return typed attribute value or empty if not present or wrong type
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getAttribute(String key, Class<T> type) {
    Object value = attributes.get(key);
    if (value != null && type.isInstance(value)) {
      return Optional.of((T) value);
    }
    return Optional.empty();
  }

  /**
   * Get all custom attributes as an immutable map.
   * @return attributes map
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * Create a new builder.
   * @return builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Create a context from a legacy RequestContext without extensions.
   * @param requestContext legacy request context
   * @return authorization context wrapping the legacy context
   */
  public static OzoneAuthorizationContext fromRequestContext(
      RequestContext requestContext) {
    return builder().requestContext(requestContext).build();
  }

  /**
   * Builder for {@link OzoneAuthorizationContext}.
   */
  public static class Builder {
    private RequestContext requestContext;
    private String tenantId;
    private String sessionToken;
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * Set the legacy request context (required).
     * @param ctx request context
     * @return this builder
     */
    public Builder requestContext(RequestContext ctx) {
      this.requestContext = ctx;
      return this;
    }

    /**
     * Set the tenant ID for multi-tenant authorization.
     * @param tenant tenant identifier (e.g., "acme")
     * @return this builder
     */
    public Builder tenantId(String tenant) {
      this.tenantId = tenant;
      return this;
    }

    /**
     * Set the session token (e.g., OIDC ID token, AWS STS session token).
     * @param token token string
     * @return this builder
     */
    public Builder sessionToken(String token) {
      this.sessionToken = token;
      return this;
    }

    /**
     * Add a custom attribute.
     * Common attributes:
     * - "oidc_subject": OIDC subject claim
     * - "oidc_groups": List of groups from IdP
     * - "oidc_claims": Full JWT claims map
     * - "tenant_roles": List of tenant-specific roles
     * - "access_key_id": S3 access key ID for temp credentials
     *
     * @param key attribute key
     * @param value attribute value
     * @return this builder
     */
    public Builder attribute(String key, Object value) {
      this.attributes.put(key, value);
      return this;
    }

    /**
     * Add multiple attributes at once.
     * @param attrs attributes map
     * @return this builder
     */
    public Builder attributes(Map<String, Object> attrs) {
      this.attributes.putAll(attrs);
      return this;
    }

    /**
     * Build the authorization context.
     * @return immutable context instance
     * @throws IllegalArgumentException if requestContext is null
     */
    public OzoneAuthorizationContext build() {
      if (requestContext == null) {
        throw new IllegalArgumentException(
            "requestContext is required");
      }
      return new OzoneAuthorizationContext(this);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("OzoneAuthorizationContext{");
    sb.append("user=").append(requestContext.getClientUgi().getShortUserName());
    if (tenantId != null) {
      sb.append(", tenant=").append(tenantId);
    }
    if (sessionToken != null) {
      sb.append(", hasSessionToken=true");
    }
    if (!attributes.isEmpty()) {
      sb.append(", attributes=").append(attributes.keySet());
    }
    sb.append("}");
    return sb.toString();
  }
}
