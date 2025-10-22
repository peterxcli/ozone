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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents temporary AWS-style credentials issued by STS.
 */
public final class TemporaryCredentials {
  
  private final String accessKeyId;
  private final String secretAccessKey;
  private final String sessionToken;
  private final Instant expiration;
  private final String principal;
  private final String roleArn;
  private final String sessionName;
  private final Map<String, String> attributes;

  private TemporaryCredentials(Builder builder) {
    this.accessKeyId = builder.accessKeyId;
    this.secretAccessKey = builder.secretAccessKey;
    this.sessionToken = builder.sessionToken;
    this.expiration = builder.expiration;
    this.principal = builder.principal;
    this.roleArn = builder.roleArn;
    this.sessionName = builder.sessionName;
    this.attributes = new HashMap<>(builder.attributes);
  }

  public String getAccessKeyId() {
    return accessKeyId;
  }

  public String getSecretAccessKey() {
    return secretAccessKey;
  }

  public String getSessionToken() {
    return sessionToken;
  }

  public Instant getExpiration() {
    return expiration;
  }

  public String getPrincipal() {
    return principal;
  }

  public String getRoleArn() {
    return roleArn;
  }

  public String getSessionName() {
    return sessionName;
  }

  public Map<String, String> getAttributes() {
    return new HashMap<>(attributes);
  }

  public String getAttribute(String key) {
    return attributes.get(key);
  }

  public boolean isExpired() {
    return Instant.now().isAfter(expiration);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TemporaryCredentials that = (TemporaryCredentials) o;
    return Objects.equals(accessKeyId, that.accessKeyId) &&
        Objects.equals(sessionToken, that.sessionToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accessKeyId, sessionToken);
  }

  @Override
  public String toString() {
    return "TemporaryCredentials{" +
        "accessKeyId='" + accessKeyId + '\'' +
        ", expiration=" + expiration +
        ", principal='" + principal + '\'' +
        ", roleArn='" + roleArn + '\'' +
        ", sessionName='" + sessionName + '\'' +
        '}';
  }

  /**
   * Builder for TemporaryCredentials.
   */
  public static class Builder {
    private String accessKeyId;
    private String secretAccessKey;
    private String sessionToken;
    private Instant expiration;
    private String principal;
    private String roleArn;
    private String sessionName;
    private Map<String, String> attributes = new HashMap<>();

    public Builder setAccessKeyId(String accessKeyId) {
      this.accessKeyId = accessKeyId;
      return this;
    }

    public Builder setSecretAccessKey(String secretAccessKey) {
      this.secretAccessKey = secretAccessKey;
      return this;
    }

    public Builder setSessionToken(String sessionToken) {
      this.sessionToken = sessionToken;
      return this;
    }

    public Builder setExpiration(Instant expiration) {
      this.expiration = expiration;
      return this;
    }

    public Builder setPrincipal(String principal) {
      this.principal = principal;
      return this;
    }

    public Builder setRoleArn(String roleArn) {
      this.roleArn = roleArn;
      return this;
    }

    public Builder setSessionName(String sessionName) {
      this.sessionName = sessionName;
      return this;
    }

    public Builder addAttribute(String key, String value) {
      this.attributes.put(key, value);
      return this;
    }

    public Builder setAttributes(Map<String, String> attributes) {
      this.attributes = new HashMap<>(attributes);
      return this;
    }

    public TemporaryCredentials build() {
      Objects.requireNonNull(accessKeyId, "accessKeyId is required");
      Objects.requireNonNull(secretAccessKey, "secretAccessKey is required");
      Objects.requireNonNull(sessionToken, "sessionToken is required");
      Objects.requireNonNull(expiration, "expiration is required");
      Objects.requireNonNull(principal, "principal is required");
      return new TemporaryCredentials(this);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }
}

