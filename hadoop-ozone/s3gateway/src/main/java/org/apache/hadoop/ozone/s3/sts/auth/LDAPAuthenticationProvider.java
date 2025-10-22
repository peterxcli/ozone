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
 * LDAP authentication provider.
 */
public class LDAPAuthenticationProvider implements AuthenticationProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(LDAPAuthenticationProvider.class);

  private final OzoneConfiguration config;
  private String ldapUrl;
  private String baseDn;
  private String userDnPattern;
  private String groupSearchBase;
  private String groupSearchFilter;
  private boolean enabled;

  public LDAPAuthenticationProvider(OzoneConfiguration config) {
    this.config = config;
  }

  @Override
  public void initialize() throws IOException {
    ldapUrl = config.get(STSConfigKeys.OZONE_STS_LDAP_URL);
    baseDn = config.get(STSConfigKeys.OZONE_STS_LDAP_BASE_DN);
    userDnPattern = config.get(
        STSConfigKeys.OZONE_STS_LDAP_USER_DN_PATTERN,
        STSConfigKeys.OZONE_STS_LDAP_USER_DN_PATTERN_DEFAULT);
    groupSearchBase = config.get(STSConfigKeys.OZONE_STS_LDAP_GROUP_SEARCH_BASE);
    groupSearchFilter = config.get(
        STSConfigKeys.OZONE_STS_LDAP_GROUP_SEARCH_FILTER,
        STSConfigKeys.OZONE_STS_LDAP_GROUP_SEARCH_FILTER_DEFAULT);

    enabled = ldapUrl != null && !ldapUrl.isEmpty();

    if (enabled) {
      LOG.info("LDAP authentication provider initialized. URL: {}", ldapUrl);
    }
  }

  @Override
  public AuthenticationResult authenticate(AuthenticationRequest request)
      throws IOException {
    
    if (!enabled) {
      return AuthenticationResult.failure("LDAP not configured");
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
      // Parse LDAP URL
      URI uri = new URI(ldapUrl);
      String host = uri.getHost();
      int port = uri.getPort();
      if (port == -1) {
        port = ldapUrl.startsWith("ldaps://") ? 636 : 389;
      }
      boolean useSsl = ldapUrl.startsWith("ldaps://");

      // Create connection
      connection = new LdapNetworkConnection(host, port, useSsl);
      connection.connect();

      // Build user DN
      String userDn;
      if (baseDn != null && !baseDn.isEmpty()) {
        userDn = MessageFormat.format(userDnPattern + "," + baseDn, username);
      } else {
        userDn = MessageFormat.format(userDnPattern, username);
      }

      // Attempt bind with user credentials
      try {
        connection.bind(userDn, password);
      } catch (Exception e) {
        LOG.debug("LDAP bind failed for user: {}", username, e);
        return AuthenticationResult.failure(
            "Invalid username or password");
      }

      // Authentication successful
      LOG.info("Successfully authenticated LDAP user: {}", username);

      // Build result
      AuthenticationResult.Builder resultBuilder =
          AuthenticationResult.newBuilder()
              .setSuccess(true)
              .setPrincipal(username);

      // Get user groups if configured
      if (groupSearchBase != null && !groupSearchBase.isEmpty()) {
        List<String> groups = getUserGroups(connection, userDn, username);
        groups.forEach(resultBuilder::addGroup);
      }

      return resultBuilder.build();

    } catch (Exception e) {
      LOG.error("LDAP authentication failed for user: {}", username, e);
      return AuthenticationResult.failure(
          "LDAP authentication failed: " + e.getMessage());
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (Exception e) {
          LOG.warn("Error closing LDAP connection", e);
        }
      }
    }
  }

  private List<String> getUserGroups(LdapConnection connection,
      String userDn, String username) {
    List<String> groups = new ArrayList<>();
    
    try {
      // Search for groups
      String filter = MessageFormat.format(groupSearchFilter, userDn);
      EntryCursor cursor = connection.search(
          groupSearchBase,
          filter,
          SearchScope.SUBTREE,
          "cn", "name"
      );

      while (cursor.next()) {
        Entry entry = cursor.get();
        String groupName = entry.get("cn") != null ?
            entry.get("cn").getString() :
            entry.get("name").getString();
        if (groupName != null) {
          groups.add(groupName);
        }
      }

      cursor.close();
      LOG.debug("Found {} groups for user: {}", groups.size(), username);

    } catch (Exception e) {
      LOG.warn("Failed to retrieve groups for user: {}", username, e);
    }

    return groups;
  }

  @Override
  public ProviderType getProviderType() {
    return ProviderType.LDAP;
  }

  @Override
  public boolean supports(String authenticationType) {
    return "LDAP".equalsIgnoreCase(authenticationType);
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

