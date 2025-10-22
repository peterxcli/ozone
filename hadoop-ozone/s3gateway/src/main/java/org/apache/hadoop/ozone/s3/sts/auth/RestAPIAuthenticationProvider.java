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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API authentication provider for external authentication services.
 */
public class RestAPIAuthenticationProvider implements AuthenticationProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(RestAPIAuthenticationProvider.class);

  private final OzoneConfiguration config;
  private final ObjectMapper objectMapper;
  private String authUrl;
  private Map<String, String> headers;
  private int timeout;
  private String usernameField;
  private String passwordField;
  private String tokenField;
  private Set<Integer> successStatusCodes;
  private boolean enabled;
  private CloseableHttpClient httpClient;

  public RestAPIAuthenticationProvider(OzoneConfiguration config) {
    this.config = config;
    this.objectMapper = new ObjectMapper();
    this.headers = new HashMap<>();
  }

  @Override
  public void initialize() throws IOException {
    authUrl = config.get(STSConfigKeys.OZONE_STS_REST_AUTH_URL);
    timeout = config.getInt(
        STSConfigKeys.OZONE_STS_REST_AUTH_TIMEOUT,
        STSConfigKeys.OZONE_STS_REST_AUTH_TIMEOUT_DEFAULT);
    usernameField = config.get(
        STSConfigKeys.OZONE_STS_REST_USERNAME_FIELD,
        STSConfigKeys.OZONE_STS_REST_USERNAME_FIELD_DEFAULT);
    passwordField = config.get(
        STSConfigKeys.OZONE_STS_REST_PASSWORD_FIELD,
        STSConfigKeys.OZONE_STS_REST_PASSWORD_FIELD_DEFAULT);
    tokenField = config.get(
        STSConfigKeys.OZONE_STS_REST_TOKEN_FIELD,
        STSConfigKeys.OZONE_STS_REST_TOKEN_FIELD_DEFAULT);

    // Parse success status codes
    String statusCodesStr = config.get(
        STSConfigKeys.OZONE_STS_REST_SUCCESS_STATUS_CODES,
        STSConfigKeys.OZONE_STS_REST_SUCCESS_STATUS_CODES_DEFAULT);
    successStatusCodes = new HashSet<>();
    for (String code : statusCodesStr.split(",")) {
      try {
        successStatusCodes.add(Integer.parseInt(code.trim()));
      } catch (NumberFormatException e) {
        LOG.warn("Invalid status code: {}", code);
      }
    }

    // Parse headers
    String headersStr = config.get(STSConfigKeys.OZONE_STS_REST_AUTH_HEADERS);
    if (headersStr != null && !headersStr.isEmpty()) {
      for (String header : headersStr.split(",")) {
        String[] parts = header.split(":");
        if (parts.length == 2) {
          headers.put(parts[0].trim(), parts[1].trim());
        }
      }
    }

    enabled = authUrl != null && !authUrl.isEmpty();

    if (enabled) {
      // Create HTTP client with timeout
      RequestConfig requestConfig = RequestConfig.custom()
          .setConnectTimeout(timeout)
          .setSocketTimeout(timeout)
          .build();
      
      httpClient = HttpClients.custom()
          .setDefaultRequestConfig(requestConfig)
          .build();

      LOG.info("REST API authentication provider initialized. URL: {}",
          authUrl);
    }
  }

  @Override
  public AuthenticationResult authenticate(AuthenticationRequest request)
      throws IOException {
    
    if (!enabled) {
      return AuthenticationResult.failure("REST API auth not configured");
    }

    // Build authentication request
    Map<String, Object> authRequest = new HashMap<>();
    
    if (request.getUsername() != null && request.getPassword() != null) {
      authRequest.put(usernameField, request.getUsername());
      authRequest.put(passwordField, request.getPassword());
    } else if (request.getToken() != null) {
      authRequest.put(tokenField, request.getToken());
    } else {
      return AuthenticationResult.failure(
          "Username/password or token required");
    }

    // Add custom attributes
    authRequest.putAll(request.getAttributes());

    try {
      // Create HTTP request
      HttpPost httpPost = new HttpPost(authUrl);
      
      // Set headers
      headers.forEach(httpPost::setHeader);
      httpPost.setHeader("Content-Type", "application/json");

      // Set request body
      String jsonBody = objectMapper.writeValueAsString(authRequest);
      httpPost.setEntity(new StringEntity(jsonBody));

      // Execute request
      try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
        int statusCode = response.getStatusLine().getStatusCode();
        HttpEntity entity = response.getEntity();
        String responseBody = entity != null ?
            EntityUtils.toString(entity) : "";

        // Check if authentication succeeded
        if (successStatusCodes.contains(statusCode)) {
          return parseSuccessResponse(responseBody, request.getUsername());
        } else {
          LOG.debug("REST API auth failed with status: {}", statusCode);
          return AuthenticationResult.failure(
              "Authentication failed: " + statusCode);
        }
      }

    } catch (Exception e) {
      LOG.error("REST API authentication failed", e);
      return AuthenticationResult.failure(
          "REST API auth failed: " + e.getMessage());
    }
  }

  private AuthenticationResult parseSuccessResponse(String responseBody,
      String username) {
    try {
      JsonNode response = objectMapper.readTree(responseBody);
      
      AuthenticationResult.Builder resultBuilder =
          AuthenticationResult.newBuilder()
              .setSuccess(true);

      // Extract principal/user
      String principal = username;
      if (response.has("user")) {
        principal = response.get("user").asText();
      } else if (response.has("username")) {
        principal = response.get("username").asText();
      } else if (response.has("subject")) {
        principal = response.get("subject").asText();
      }
      resultBuilder.setPrincipal(principal);

      // Extract groups
      if (response.has("groups")) {
        JsonNode groups = response.get("groups");
        if (groups.isArray()) {
          groups.forEach(group -> resultBuilder.addGroup(group.asText()));
        }
      }

      // Extract additional attributes
      if (response.has("attributes")) {
        JsonNode attributes = response.get("attributes");
        attributes.fields().forEachRemaining(entry ->
            resultBuilder.addAttribute(entry.getKey(),
                entry.getValue().asText())
        );
      }

      // Extract email if present
      if (response.has("email")) {
        resultBuilder.addAttribute("email", response.get("email").asText());
      }

      // Extract name if present
      if (response.has("name")) {
        resultBuilder.addAttribute("name", response.get("name").asText());
      }

      LOG.info("Successfully authenticated via REST API for principal: {}",
          principal);
      return resultBuilder.build();

    } catch (Exception e) {
      LOG.error("Failed to parse REST API response", e);
      return AuthenticationResult.failure(
          "Failed to parse auth response: " + e.getMessage());
    }
  }

  @Override
  public ProviderType getProviderType() {
    return ProviderType.REST_API;
  }

  @Override
  public boolean supports(String authenticationType) {
    return "REST".equalsIgnoreCase(authenticationType) ||
        "REST_API".equalsIgnoreCase(authenticationType) ||
        "HTTP".equalsIgnoreCase(authenticationType);
  }

  @Override
  public void close() {
    if (httpClient != null) {
      try {
        httpClient.close();
      } catch (IOException e) {
        LOG.warn("Error closing HTTP client", e);
      }
    }
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }
}

