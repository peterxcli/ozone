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

import java.util.HashMap;
import java.util.Map;

/**
 * Request for authentication containing credentials and metadata.
 */
public final class AuthenticationRequest {
  
  private final String username;
  private final String password;
  private final String token;
  private final String samlAssertion;
  private final String webIdentityToken;
  private final Map<String, String> attributes;

  private AuthenticationRequest(Builder builder) {
    this.username = builder.username;
    this.password = builder.password;
    this.token = builder.token;
    this.samlAssertion = builder.samlAssertion;
    this.webIdentityToken = builder.webIdentityToken;
    this.attributes = new HashMap<>(builder.attributes);
  }

  public String getUsername() {
    return username;
  }

  public String getPassword() {
    return password;
  }

  public String getToken() {
    return token;
  }

  public String getSamlAssertion() {
    return samlAssertion;
  }

  public String getWebIdentityToken() {
    return webIdentityToken;
  }

  public Map<String, String> getAttributes() {
    return new HashMap<>(attributes);
  }

  public String getAttribute(String key) {
    return attributes.get(key);
  }

  /**
   * Builder for AuthenticationRequest.
   */
  public static class Builder {
    private String username;
    private String password;
    private String token;
    private String samlAssertion;
    private String webIdentityToken;
    private Map<String, String> attributes = new HashMap<>();

    public Builder setUsername(String username) {
      this.username = username;
      return this;
    }

    public Builder setPassword(String password) {
      this.password = password;
      return this;
    }

    public Builder setToken(String token) {
      this.token = token;
      return this;
    }

    public Builder setSamlAssertion(String samlAssertion) {
      this.samlAssertion = samlAssertion;
      return this;
    }

    public Builder setWebIdentityToken(String webIdentityToken) {
      this.webIdentityToken = webIdentityToken;
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

    public AuthenticationRequest build() {
      return new AuthenticationRequest(this);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }
}

