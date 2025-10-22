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

package org.apache.hadoop.ozone.s3.sts;

/**
 * Configuration keys for STS service.
 */
public final class STSConfigKeys {
  
  private STSConfigKeys() {
  }

  public static final String OZONE_STS_PREFIX = "ozone.s3.sts.";

  // STS Service Enablement
  public static final String OZONE_STS_ENABLED =
      OZONE_STS_PREFIX + "enabled";
  public static final boolean OZONE_STS_ENABLED_DEFAULT = false;

  // Token Lifetime Configuration
  public static final String OZONE_STS_TOKEN_MAX_LIFETIME =
      OZONE_STS_PREFIX + "token.max.lifetime";
  public static final long OZONE_STS_TOKEN_MAX_LIFETIME_DEFAULT =
      43200000; // 12 hours in milliseconds

  public static final String OZONE_STS_TOKEN_DEFAULT_DURATION =
      OZONE_STS_PREFIX + "token.default.duration";
  public static final long OZONE_STS_TOKEN_DEFAULT_DURATION_DEFAULT =
      3600000; // 1 hour in milliseconds

  public static final String OZONE_STS_TOKEN_MIN_DURATION =
      OZONE_STS_PREFIX + "token.min.duration";
  public static final long OZONE_STS_TOKEN_MIN_DURATION_DEFAULT =
      900000; // 15 minutes in milliseconds

  // JWT Configuration
  public static final String OZONE_STS_JWT_SECRET =
      OZONE_STS_PREFIX + "jwt.secret";
  public static final String OZONE_STS_JWT_SECRET_DEFAULT =
      "ozone-sts-default-secret-change-in-production";

  public static final String OZONE_STS_JWT_ISSUER =
      OZONE_STS_PREFIX + "jwt.issuer";
  public static final String OZONE_STS_JWT_ISSUER_DEFAULT = "ozone-sts";

  public static final String OZONE_STS_JWT_AUDIENCE =
      OZONE_STS_PREFIX + "jwt.audience";
  public static final String OZONE_STS_JWT_AUDIENCE_DEFAULT = "s3-gateway";

  // Authentication Providers
  public static final String OZONE_STS_AUTH_PROVIDERS =
      OZONE_STS_PREFIX + "auth.providers";
  public static final String OZONE_STS_AUTH_PROVIDERS_DEFAULT = "";

  // IDP Provider Configuration
  public static final String OZONE_STS_IDP_PREFIX =
      OZONE_STS_PREFIX + "idp.";

  public static final String OZONE_STS_IDP_OIDC_ISSUER =
      OZONE_STS_IDP_PREFIX + "oidc.issuer";

  public static final String OZONE_STS_IDP_OIDC_JWKS_URL =
      OZONE_STS_IDP_PREFIX + "oidc.jwks.url";

  public static final String OZONE_STS_IDP_OIDC_CLIENT_ID =
      OZONE_STS_IDP_PREFIX + "oidc.client.id";

  public static final String OZONE_STS_IDP_SAML_METADATA_URL =
      OZONE_STS_IDP_PREFIX + "saml.metadata.url";

  public static final String OZONE_STS_IDP_SAML_ENTITY_ID =
      OZONE_STS_IDP_PREFIX + "saml.entity.id";

  // LDAP Provider Configuration
  public static final String OZONE_STS_LDAP_PREFIX =
      OZONE_STS_PREFIX + "ldap.";

  public static final String OZONE_STS_LDAP_URL =
      OZONE_STS_LDAP_PREFIX + "url";

  public static final String OZONE_STS_LDAP_BASE_DN =
      OZONE_STS_LDAP_PREFIX + "base.dn";

  public static final String OZONE_STS_LDAP_USER_DN_PATTERN =
      OZONE_STS_LDAP_PREFIX + "user.dn.pattern";
  public static final String OZONE_STS_LDAP_USER_DN_PATTERN_DEFAULT =
      "uid={0},ou=users";

  public static final String OZONE_STS_LDAP_BIND_DN =
      OZONE_STS_LDAP_PREFIX + "bind.dn";

  public static final String OZONE_STS_LDAP_BIND_PASSWORD =
      OZONE_STS_LDAP_PREFIX + "bind.password";

  public static final String OZONE_STS_LDAP_USER_SEARCH_FILTER =
      OZONE_STS_LDAP_PREFIX + "user.search.filter";
  public static final String OZONE_STS_LDAP_USER_SEARCH_FILTER_DEFAULT =
      "(uid={0})";

  public static final String OZONE_STS_LDAP_GROUP_SEARCH_BASE =
      OZONE_STS_LDAP_PREFIX + "group.search.base";

  public static final String OZONE_STS_LDAP_GROUP_SEARCH_FILTER =
      OZONE_STS_LDAP_PREFIX + "group.search.filter";
  public static final String OZONE_STS_LDAP_GROUP_SEARCH_FILTER_DEFAULT =
      "(member={0})";

  // AD Provider Configuration (extends LDAP)
  public static final String OZONE_STS_AD_PREFIX =
      OZONE_STS_PREFIX + "ad.";

  public static final String OZONE_STS_AD_DOMAIN =
      OZONE_STS_AD_PREFIX + "domain";

  public static final String OZONE_STS_AD_URL =
      OZONE_STS_AD_PREFIX + "url";

  public static final String OZONE_STS_AD_USER_DN_PATTERN =
      OZONE_STS_AD_PREFIX + "user.dn.pattern";
  public static final String OZONE_STS_AD_USER_DN_PATTERN_DEFAULT =
      "{0}@{1}";

  public static final String OZONE_STS_AD_SEARCH_BASE =
      OZONE_STS_AD_PREFIX + "search.base";

  public static final String OZONE_STS_AD_BIND_DN =
      OZONE_STS_AD_PREFIX + "bind.dn";

  public static final String OZONE_STS_AD_BIND_PASSWORD =
      OZONE_STS_AD_PREFIX + "bind.password";

  // REST API Provider Configuration
  public static final String OZONE_STS_REST_PREFIX =
      OZONE_STS_PREFIX + "rest.";

  public static final String OZONE_STS_REST_AUTH_URL =
      OZONE_STS_REST_PREFIX + "auth.url";

  public static final String OZONE_STS_REST_AUTH_METHOD =
      OZONE_STS_REST_PREFIX + "auth.method";
  public static final String OZONE_STS_REST_AUTH_METHOD_DEFAULT = "POST";

  public static final String OZONE_STS_REST_AUTH_HEADERS =
      OZONE_STS_REST_PREFIX + "auth.headers";

  public static final String OZONE_STS_REST_AUTH_TIMEOUT =
      OZONE_STS_REST_PREFIX + "auth.timeout";
  public static final int OZONE_STS_REST_AUTH_TIMEOUT_DEFAULT = 5000;

  public static final String OZONE_STS_REST_USERNAME_FIELD =
      OZONE_STS_REST_PREFIX + "username.field";
  public static final String OZONE_STS_REST_USERNAME_FIELD_DEFAULT =
      "username";

  public static final String OZONE_STS_REST_PASSWORD_FIELD =
      OZONE_STS_REST_PREFIX + "password.field";
  public static final String OZONE_STS_REST_PASSWORD_FIELD_DEFAULT =
      "password";

  public static final String OZONE_STS_REST_TOKEN_FIELD =
      OZONE_STS_REST_PREFIX + "token.field";
  public static final String OZONE_STS_REST_TOKEN_FIELD_DEFAULT = "token";

  public static final String OZONE_STS_REST_SUCCESS_STATUS_CODES =
      OZONE_STS_REST_PREFIX + "success.status.codes";
  public static final String OZONE_STS_REST_SUCCESS_STATUS_CODES_DEFAULT =
      "200,201";

  // Token Store Configuration
  public static final String OZONE_STS_TOKEN_STORE_CACHE_SIZE =
      OZONE_STS_PREFIX + "token.store.cache.size";
  public static final int OZONE_STS_TOKEN_STORE_CACHE_SIZE_DEFAULT = 10000;

  public static final String OZONE_STS_TOKEN_STORE_CACHE_EXPIRY =
      OZONE_STS_PREFIX + "token.store.cache.expiry";
  public static final long OZONE_STS_TOKEN_STORE_CACHE_EXPIRY_DEFAULT =
      3600000; // 1 hour

  // Role Configuration
  public static final String OZONE_STS_ROLE_PREFIX =
      OZONE_STS_PREFIX + "role.";

  public static final String OZONE_STS_ROLE_SESSION_NAME_REGEX =
      OZONE_STS_ROLE_PREFIX + "session.name.regex";
  public static final String OZONE_STS_ROLE_SESSION_NAME_REGEX_DEFAULT =
      "[\\w+=,.@-]+";

  public static final String OZONE_STS_ROLE_ARN_PREFIX =
      OZONE_STS_ROLE_PREFIX + "arn.prefix";
  public static final String OZONE_STS_ROLE_ARN_PREFIX_DEFAULT =
      "arn:ozone:iam::";
}

