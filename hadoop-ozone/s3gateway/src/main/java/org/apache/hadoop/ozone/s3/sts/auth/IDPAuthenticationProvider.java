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
import java.util.Base64;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.STSConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity Provider (IDP) authentication supporting OIDC and SAML.
 */
public class IDPAuthenticationProvider implements AuthenticationProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(IDPAuthenticationProvider.class);

  private final OzoneConfiguration config;
  private String oidcIssuer;
  private String oidcClientId;
  private String samlMetadataUrl;
  private boolean enabled;

  public IDPAuthenticationProvider(OzoneConfiguration config) {
    this.config = config;
  }

  @Override
  public void initialize() throws IOException {
    oidcIssuer = config.get(STSConfigKeys.OZONE_STS_IDP_OIDC_ISSUER);
    oidcClientId = config.get(STSConfigKeys.OZONE_STS_IDP_OIDC_CLIENT_ID);
    samlMetadataUrl = config.get(STSConfigKeys.OZONE_STS_IDP_SAML_METADATA_URL);

    enabled = (oidcIssuer != null && !oidcIssuer.isEmpty()) ||
        (samlMetadataUrl != null && !samlMetadataUrl.isEmpty());

    if (enabled) {
      LOG.info("IDP authentication provider initialized. OIDC: {}, SAML: {}",
          oidcIssuer != null, samlMetadataUrl != null);
    }
  }

  @Override
  public AuthenticationResult authenticate(AuthenticationRequest request)
      throws IOException {
    
    // Try OIDC web identity token first
    if (request.getWebIdentityToken() != null &&
        !request.getWebIdentityToken().isEmpty()) {
      return authenticateOIDC(request.getWebIdentityToken());
    }

    // Try SAML assertion
    if (request.getSamlAssertion() != null &&
        !request.getSamlAssertion().isEmpty()) {
      return authenticateSAML(request.getSamlAssertion());
    }

    return AuthenticationResult.failure(
        "No web identity token or SAML assertion provided");
  }

  private AuthenticationResult authenticateOIDC(String webIdentityToken) {
    if (oidcIssuer == null || oidcIssuer.isEmpty()) {
      return AuthenticationResult.failure("OIDC not configured");
    }

    try {
      // Parse JWT token without verification (for basic validation)
      // In production, this should verify the signature using JWKS
      String[] parts = webIdentityToken.split("\\.");
      if (parts.length < 2) {
        return AuthenticationResult.failure("Invalid JWT token format");
      }

      String payloadJson = new String(
          Base64.getUrlDecoder().decode(parts[1]));
      ObjectMapper mapper = new ObjectMapper();
      JsonNode payload = mapper.readTree(payloadJson);

      // Validate issuer
      String tokenIssuer = payload.get("iss").asText();
      if (!oidcIssuer.equals(tokenIssuer)) {
        return AuthenticationResult.failure("Invalid token issuer");
      }

      // Validate client ID (audience)
      if (oidcClientId != null && !oidcClientId.isEmpty()) {
        JsonNode aud = payload.get("aud");
        String audience = aud != null ? aud.asText() : null;
        if (audience == null || !oidcClientId.equals(audience)) {
          return AuthenticationResult.failure("Invalid token audience");
        }
      }

      // Extract subject (user)
      String subject = payload.get("sub").asText();
      if (subject == null || subject.isEmpty()) {
        return AuthenticationResult.failure("No subject in token");
      }

      // Check expiration
      long exp = payload.get("exp").asLong();
      if (System.currentTimeMillis() / 1000 > exp) {
        return AuthenticationResult.failure("Token expired");
      }

      // Build result
      AuthenticationResult.Builder resultBuilder =
          AuthenticationResult.newBuilder()
              .setSuccess(true)
              .setPrincipal(subject);

      // Extract email if present
      if (payload.has("email")) {
        resultBuilder.addAttribute("email", payload.get("email").asText());
      }

      // Extract name if present
      if (payload.has("name")) {
        resultBuilder.addAttribute("name", payload.get("name").asText());
      }

      // Extract groups if present
      if (payload.has("groups")) {
        JsonNode groups = payload.get("groups");
        if (groups.isArray()) {
          groups.forEach(group -> resultBuilder.addGroup(group.asText()));
        }
      }

      LOG.info("Successfully authenticated OIDC token for subject: {}",
          subject);
      return resultBuilder.build();

    } catch (Exception e) {
      LOG.error("Failed to authenticate OIDC token", e);
      return AuthenticationResult.failure(
          "OIDC authentication failed: " + e.getMessage());
    }
  }

  private AuthenticationResult authenticateSAML(String samlAssertion) {
    if (samlMetadataUrl == null || samlMetadataUrl.isEmpty()) {
      return AuthenticationResult.failure("SAML not configured");
    }

    try {
      // Decode base64 SAML assertion
      byte[] decodedAssertion =
          Base64.getDecoder().decode(samlAssertion);
      String assertionXml = new String(decodedAssertion);

      // In a production implementation, this would:
      // 1. Parse the SAML assertion XML
      // 2. Validate the signature
      // 3. Check conditions (NotBefore, NotOnOrAfter)
      // 4. Verify the issuer matches expected entity ID
      // 5. Extract subject and attributes

      // For now, return a basic implementation
      LOG.warn("SAML authentication is not fully implemented. " +
          "Assertion length: {}", assertionXml.length());

      return AuthenticationResult.failure(
          "SAML authentication not fully implemented");

    } catch (Exception e) {
      LOG.error("Failed to authenticate SAML assertion", e);
      return AuthenticationResult.failure(
          "SAML authentication failed: " + e.getMessage());
    }
  }

  @Override
  public ProviderType getProviderType() {
    return ProviderType.IDP;
  }

  @Override
  public boolean supports(String authenticationType) {
    if (authenticationType == null) {
      return false;
    }
    String type = authenticationType.toUpperCase();
    return "OIDC".equals(type) || "SAML".equals(type) ||
        "WEB_IDENTITY".equals(type);
  }

  @Override
  public void close() {
    // No resources to clean up
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }
}

