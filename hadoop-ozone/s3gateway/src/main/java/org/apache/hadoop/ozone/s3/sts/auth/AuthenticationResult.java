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

package org.apache.hadoop.ozone.s3.sts.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of authentication attempt.
 */
public final class AuthenticationResult {
  
  private final boolean success;
  private final String principal;
  private final String errorMessage;
  private final List<String> groups;
  private final Map<String, String> attributes;

  private AuthenticationResult(Builder builder) {
    this.success = builder.success;
    this.principal = builder.principal;
    this.errorMessage = builder.errorMessage;
    this.groups = new ArrayList<>(builder.groups);
    this.attributes = new HashMap<>(builder.attributes);
  }

  public boolean isSuccess() {
    return success;
  }

  public String getPrincipal() {
    return principal;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public List<String> getGroups() {
    return new ArrayList<>(groups);
  }

  public Map<String, String> getAttributes() {
    return new HashMap<>(attributes);
  }

  public String getAttribute(String key) {
    return attributes.get(key);
  }

  /**
   * Builder for AuthenticationResult.
   */
  public static class Builder {
    private boolean success;
    private String principal;
    private String errorMessage;
    private List<String> groups = new ArrayList<>();
    private Map<String, String> attributes = new HashMap<>();

    public Builder setSuccess(boolean success) {
      this.success = success;
      return this;
    }

    public Builder setPrincipal(String principal) {
      this.principal = principal;
      return this;
    }

    public Builder setErrorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    public Builder addGroup(String group) {
      this.groups.add(group);
      return this;
    }

    public Builder setGroups(List<String> groups) {
      this.groups = new ArrayList<>(groups);
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

    public AuthenticationResult build() {
      return new AuthenticationResult(this);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static AuthenticationResult success(String principal) {
    return newBuilder()
        .setSuccess(true)
        .setPrincipal(principal)
        .build();
  }

  public static AuthenticationResult failure(String errorMessage) {
    return newBuilder()
        .setSuccess(false)
        .setErrorMessage(errorMessage)
        .build();
  }
}

