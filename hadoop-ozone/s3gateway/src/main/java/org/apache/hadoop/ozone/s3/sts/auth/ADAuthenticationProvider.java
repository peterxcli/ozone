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

import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Active Directory authentication provider.
 */
public class ADAuthenticationProvider implements AuthenticationProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(ADAuthenticationProvider.class);

  private final OzoneConfiguration config;
  private String adUrl;
  private String adDomain;
  private String userDnPattern;
  private String searchBase;
  private boolean enabled;

  public ADAuthenticationProvider(OzoneConfiguration config) {
    this.config = config;
  }

  @Override
  public void initialize() throws IOException {
    adUrl = config.get(STSConfigKeys.OZONE_STS_AD_URL);
    adDomain = config.get(STSConfigKeys.OZONE_STS_AD_DOMAIN);
    userDnPattern = config.get(
        STSConfigKeys.OZONE_STS_AD_USER_DN_PATTERN,
        STSConfigKeys.OZONE_STS_AD_USER_DN_PATTERN_DEFAULT);
    searchBase = config.get(STSConfigKeys.OZONE_STS_AD_SEARCH_BASE);

    enabled = adUrl != null && !adUrl.isEmpty() &&
        adDomain != null && !adDomain.isEmpty();

    if (enabled) {
      LOG.info("AD authentication provider initialized. URL: {}, Domain: {}",
          adUrl, adDomain);
    }
  }

  @Override
  public AuthenticationResult authenticate(AuthenticationRequest request)
      throws IOException {
    
    if (!enabled) {
      return AuthenticationResult.failure("Active Directory not configured");
    }

    String username = request.getUsername();
    String password = request.getPassword();

    if (username == null || username.isEmpty() ||
        password == null || password.isEmpty()) {
      return AuthenticationResult.failure(
          "Username and password are required");
    }

    LdapConnection connection = null;
    try {
      // Parse AD URL
      URI uri = new URI(adUrl);
      String host = uri.getHost();
      int port = uri.getPort();
      if (port == -1) {
        port = adUrl.startsWith("ldaps://") ? 636 : 389;
      }
      boolean useSsl = adUrl.startsWith("ldaps://");

      // Create connection
      connection = new LdapNetworkConnection(host, port, useSsl);
      connection.connect();

      // Build user principal name (UPN) for AD
      String userPrincipal;
      if (username.contains("@")) {
        // Already has domain
        userPrincipal = username;
      } else {
        // Add domain
        userPrincipal = MessageFormat.format(userDnPattern, username, adDomain);
      }

      // Attempt bind with user credentials
      try {
        connection.bind(userPrincipal, password);
      } catch (Exception e) {
        LOG.debug("AD bind failed for user: {}", username, e);
        return AuthenticationResult.failure(
            "Invalid username or password");
      }

      // Authentication successful
      LOG.info("Successfully authenticated AD user: {}", username);

      // Build result
      AuthenticationResult.Builder resultBuilder =
          AuthenticationResult.newBuilder()
              .setSuccess(true)
              .setPrincipal(username)
              .addAttribute("domain", adDomain);

      // Get user groups if search base is configured
      if (searchBase != null && !searchBase.isEmpty()) {
        List<String> groups = getUserGroups(connection, username);
        groups.forEach(resultBuilder::addGroup);
      }

      return resultBuilder.build();

    } catch (Exception e) {
      LOG.error("AD authentication failed for user: {}", username, e);
      return AuthenticationResult.failure(
          "AD authentication failed: " + e.getMessage());
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (Exception e) {
          LOG.warn("Error closing AD connection", e);
        }
      }
    }
  }

  private List<String> getUserGroups(LdapConnection connection,
      String username) {
    List<String> groups = new ArrayList<>();
    
    try {
      // Search for user's group memberships
      // AD stores groups in memberOf attribute
      String filter = "(sAMAccountName=" + username + ")";
      EntryCursor cursor = connection.search(
          searchBase,
          filter,
          SearchScope.SUBTREE,
          "memberOf"
      );

      while (cursor.next()) {
        Entry entry = cursor.get();
        if (entry.get("memberOf") != null) {
          entry.get("memberOf").forEach(value -> {
            String groupDn = value.getString();
            // Extract CN from DN
            String cn = extractCN(groupDn);
            if (cn != null) {
              groups.add(cn);
            }
          });
        }
      }

      cursor.close();
      LOG.debug("Found {} groups for AD user: {}", groups.size(), username);

    } catch (Exception e) {
      LOG.warn("Failed to retrieve groups for AD user: {}", username, e);
    }

    return groups;
  }

  private String extractCN(String dn) {
    if (dn == null || !dn.startsWith("CN=")) {
      return null;
    }
    int commaIndex = dn.indexOf(',');
    if (commaIndex > 0) {
      return dn.substring(3, commaIndex);
    }
    return dn.substring(3);
  }

  @Override
  public ProviderType getProviderType() {
    return ProviderType.AD;
  }

  @Override
  public boolean supports(String authenticationType) {
    return "AD".equalsIgnoreCase(authenticationType) ||
        "ACTIVE_DIRECTORY".equalsIgnoreCase(authenticationType);
  }

  @Override
  public void close() {
    // No persistent connections to close
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }
}

