# Apache Ozone Admin Gateway IAM & Multi-Tenancy Authorization Proposal

**Version:** 1.0  
**Date:** 2025-10-14  
**Status:** Draft  
**Authors:** Platform Engineering Team

---

## Executive Summary

This document outlines a comprehensive design for implementing a modern Identity and Access Management (IAM) system with multi-tenancy support for Apache Ozone's S3 Gateway. Given schedule pressure, we propose a phased MVP approach:

**Phase 1 (MVP - 6 weeks)**: Apache Ranger authorization + OAuth2 authentication  
**Phase 2 (6 weeks)**: Migration to SpiceDB for advanced ReBAC  
**Phase 3 (4 weeks)**: AWS-style ACL & bucket policy compatibility

**Total Timeline:** 16 weeks for full implementation

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Modern IAM Features](#2-modern-iam-features)
3. [Solution Comparison](#3-solution-comparison)
4. [MVP: Apache Ranger Implementation](#4-mvp-apache-ranger-implementation)
5. [Migration to SpiceDB](#5-migration-to-spicedb)
6. [AWS ACL/Policy Compatibility](#6-aws-aclpolicy-compatibility)
7. [Alternative Solutions](#7-alternative-solutions)
8. [Security & Compliance](#8-security--compliance)
9. [Testing & Rollout](#9-testing--rollout)
10. [Implementation Timeline](#10-implementation-timeline)

---

## 1. Architecture Overview

### 1.1 System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Client Layer                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ S3 CLI   │  │ SDK      │  │ Admin UI │  │ Programmatic API │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────────┬─────────┘   │
└───────┼─────────────┼─────────────┼─────────────────┼──────────────┘
        │             │             │                  │
        │ SigV4       │ SigV4       │ OAuth2          │ OAuth2
        ▼             ▼             ▼                  ▼
┌────────────────────────────────────────────────────────────────────┐
│                     API Gateway Layer                               │
│  ┌────────────────────────┐    ┌───────────────────────────────┐  │
│  │    S3 Gateway          │    │    Admin Gateway REST API     │  │
│  │  ┌──────────────────┐  │    │  ┌─────────────────────────┐ │  │
│  │  │ AuthFilter       │  │    │  │ OAuth2Filter            │ │  │
│  │  │ (SigV4)          │  │    │  │ (JWT Validation)        │ │  │
│  │  └──────────────────┘  │    │  └─────────────────────────┘ │  │
│  │  ┌──────────────────┐  │    │  ┌─────────────────────────┐ │  │
│  │  │ Tenant Context   │  │    │  │ TenantEndpoint          │ │  │
│  │  │ Extractor        │  │    │  │ PolicyEndpoint          │ │  │
│  │  └──────────────────┘  │    │  │ RoleEndpoint            │ │  │
│  └────────────────────────┘    │  │ UserEndpoint            │ │  │
│                                 │  └─────────────────────────┘ │  │
└────────────────────────────────────────────────────────────────────┘
                        │                          │
                        ▼                          ▼
┌────────────────────────────────────────────────────────────────────┐
│                  Ozone Manager (OM) Layer                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │           OMMultiTenantManager                               │  │
│  │  ┌────────────────┐  ┌────────────────┐  ┌───────────────┐  │  │
│  │  │ AuthorizerOp   │  │ CacheOp        │  │ TenantCache   │  │  │
│  │  │ (Ranger/SpiceDB)│  │                │  │               │  │  │
│  │  └────────────────┘  └────────────────┘  └───────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │           OM Metadata Manager                                │  │
│  │  - TenantStateTable                                          │  │
│  │  - TenantAccessIdTable                                       │  │
│  │  - PrincipalToAccessIdsTable                                 │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
                        │                          │
                        ▼                          ▼
┌────────────────────────────────────────────────────────────────────┐
│              Authorization Layer (Pluggable)                        │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │
│  │  Apache Ranger   │  │    SpiceDB       │  │  Native ACLs    │  │
│  │  (MVP)           │  │  (Future)        │  │  (Fallback)     │  │
│  │                  │  │                  │  │                 │  │
│  │ - Policies       │  │ - Relations      │  │ - Bucket ACLs   │  │
│  │ - Roles          │  │ - Permissions    │  │ - Object ACLs   │  │
│  │ - Audit Logs     │  │ - Zedtokens      │  │                 │  │
│  └──────────────────┘  └──────────────────┘  └─────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────┐
│              External Services Layer                                │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │
│  │  OAuth2/OIDC IdP │  │  Audit System    │  │  KMS/Vault      │  │
│  │  (Keycloak/Okta) │  │  (Splunk/ELK)    │  │  (Secret Mgmt)  │  │
│  └──────────────────┘  └──────────────────┘  └─────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

### 1.2 Request Flow

#### S3 Operation Flow (with Tenant Context)
```
1. Client → S3 Gateway
   - SigV4 signed request with accessKeyId
   - Extract: bucket, object, operation

2. S3 Gateway → Authorization Filter
   - Validate SigV4 signature
   - Map accessKeyId → (tenantId, principal)
   - Create tenant context

3. S3 Gateway → OM
   - Forward request with:
     * Principal (user)
     * Tenant ID
     * Resource (volume/bucket/key)
     * Operation (READ/WRITE/DELETE)

4. OM → OMMultiTenantManager
   - Lookup tenant from access ID
   - Get tenant volume name
   - Check tenant-specific permissions

5. OMMultiTenantManager → Ranger/SpiceDB
   - Build authorization request:
     * User: principal
     * Resource: /{tenantVolume}/{bucket}/{key}
     * Action: s3:GetObject / s3:PutObject / etc.
   - Get authorization decision (ALLOW/DENY)

6. Ranger/SpiceDB → OM
   - Return decision + audit context

7. OM → S3 Gateway
   - ALLOW: Execute operation
   - DENY: Return 403 Forbidden

8. Response → Client
   - Success: 200 OK with data
   - Failure: 403/404/500
```

#### Admin API Flow (OAuth2)
```
1. Client → Admin Gateway
   - OAuth2 Bearer token in Authorization header

2. Admin Gateway → OAuth2Filter
   - Validate JWT signature
   - Check token expiration
   - Extract claims: sub, email, groups

3. OAuth2Filter → Authorization
   - Map claims to Ozone principal
   - Check admin permissions

4. Admin Gateway → OM Client
   - createTenant(tenantId)
   - assignUserToTenant(user, tenantId, accessId)

5. OM → OMMultiTenantManager
   - Pre-execute: AuthorizerOp
     * Create Ranger roles (tenantId-UserRole, tenantId-AdminRole)
     * Create Ranger policies (volume, bucket)
   - Persist to DB: TenantStateTable
   - Update cache: CacheOp

6. Response → Client
   - 201 Created with tenant details
```

---

## 2. Modern IAM Features

### 2.1 Core Features & Justification

| Feature | Description | Justification | Priority |
|---------|-------------|---------------|----------|
| **RBAC** | Role-Based Access Control with hierarchical roles | Essential for managing permissions at scale. Allows tenant admins to manage users without cluster admin involvement. | P0 (MVP) |
| **Fine-grained Permissions** | Bucket/object-level permissions with path patterns | Required for complex use cases: shared buckets, cross-tenant access, prefix-based delegation. | P0 (MVP) |
| **OAuth2/OIDC** | Standard authentication via external IdP | Enables SSO, reduces credential management burden, enterprise requirement for federated identity. | P0 (MVP) |
| **Audit Logging** | Immutable audit trail of all authorization decisions | Compliance requirement (SOC2, GDPR), security forensics, regulatory reporting. | P0 (MVP) |
| **Key/Secret Management** | Secure storage and rotation of access keys | Prevents credential leakage, automated rotation reduces breach risk, supports short-lived credentials. | P0 (MVP) |
| **MFA Support** | Multi-factor authentication for sensitive operations | Security enhancement for admin operations, compliance requirement for financial/healthcare sectors. | P1 (Phase 2) |
| **Policy Versioning** | Track and rollback policy changes | Change management, audit trail, enable safe policy experimentation. | P1 (Phase 2) |
| **Temporary Credentials (STS)** | Time-bound access tokens | Least privilege access, support for job-specific credentials, reduces long-term credential exposure. | P1 (Phase 2) |
| **Attribute-Based Access (ABAC)** | Policies based on user/resource attributes | Dynamic authorization (e.g., time-based, IP-based), reduces policy proliferation. | P2 (Phase 3) |
| **Cross-Account Access** | Delegate access between tenants | Business requirement for data sharing, partnerships, M&A scenarios. | P2 (Phase 3) |

### 2.2 Feature Implementation Mapping

```
MVP (Ranger):
✓ RBAC - Ranger roles (tenantId-UserRole, tenantId-AdminRole)
✓ Fine-grained permissions - Ranger policies with path patterns
✓ OAuth2 - Admin Gateway OAuth2Filter + Keycloak integration
✓ Audit logging - Ranger audit to Solr/HDFS
✓ Key management - OM TenantAccessIdTable

Phase 2 (SpiceDB):
✓ MFA - SpiceDB context tuples + policy conditions
✓ Policy versioning - SpiceDB schema versioning
✓ STS - SpiceDB with TTL relationships
✓ ABAC - SpiceDB computed permissions

Phase 3 (AWS Compatibility):
✓ Cross-account - Bucket policies with principal ARNs
✓ Advanced ABAC - Condition keys in bucket policies
```

---

## 3. Solution Comparison

### 3.1 Comparison Matrix

| Criteria | Option A: Ranger (MVP) | Option B: SpiceDB | Option C: Keycloak + Custom |
|----------|------------------------|-------------------|------------------------------|
| **Pros** | ✓ Native Ozone integration<br>✓ Existing codebase<br>✓ Team familiarity<br>✓ Mature audit capabilities<br>✓ Faster MVP (4-6 weeks) | ✓ Modern ReBAC model<br>✓ Excellent multi-tenancy<br>✓ Horizontal scalability<br>✓ Flexible schema<br>✓ Strong consistency<br>✓ Google Zanzibar-inspired | ✓ Full OAuth2/OIDC stack<br>✓ Enterprise SSO<br>✓ Built-in user management<br>✓ Fine-grained permissions<br>✓ Well-documented |
| **Cons** | ✗ Limited OAuth2 support<br>✗ Scaling challenges (1M+ policies)<br>✗ No ReBAC<br>✗ Complex policy sync | ✗ External dependency<br>✗ Learning curve<br>✗ Additional ops overhead<br>✗ Schema design complexity | ✗ Two systems (auth + authz)<br>✗ Complex integration<br>✗ Custom authz logic<br>✗ Higher maintenance |
| **Performance** | 10-50ms authorization<br>500-1K ops/sec | <5ms with caching<br>10K+ ops/sec<br>Horizontal scaling | 20-100ms (network hops)<br>Variable based on deployment |
| **Scalability** | Vertical (single Ranger)<br>Moderate (100K policies) | Excellent horizontal<br>Millions of relationships | Good (depends on DB) |
| **Complexity** | Medium | Medium-High | High |
| **Ops Overhead** | Low (existing infra) | Medium (new service) | High (two services) |
| **Timeline** | 4-6 weeks | 8-12 weeks | 10-14 weeks |
| **Cost** | Low (use existing) | Medium (compute + storage) | Medium-High (licenses) |
| **Multi-tenancy** | Good (namespace isolation) | Excellent (native) | Medium (requires design) |
| **Audit** | Excellent (Ranger audit) | Good (changelog) | Variable |
| **AWS Parity** | Medium (custom mapping) | High (flexible schema) | Low |

### 3.2 Recommendation

**Selected Approach: Phased Migration**

1. **MVP: Ranger** (Weeks 1-6)
   - Fastest time-to-market
   - Leverages existing Ozone integration
   - Proven in production
   - Meets immediate business needs

2. **Evolution: SpiceDB** (Weeks 7-12)
   - Better scalability for growth
   - Modern ReBAC model
   - Easier AWS compatibility
   - Future-proof architecture

3. **Enhancement: AWS ACL/Policy** (Weeks 13-16)
   - S3 API compatibility
   - Familiar to users
   - Reduced migration friction

**Rationale:**
- Schedule pressure requires fast MVP → Ranger is quickest
- Growth trajectory demands scalability → SpiceDB provides this
- User experience needs AWS compatibility → Final phase addresses this

---

## 4. MVP: Apache Ranger Implementation

### 4.1 Code Structure

#### 4.1.1 New Modules in `hadoop-ozone/admin-gateway/`

```
hadoop-ozone/admin-gateway/
├── pom.xml (UPDATE: add OAuth2, JAX-RS dependencies)
└── src/main/java/org/apache/ozone/admin/
    ├── auth/
    │   ├── OAuth2Filter.java                    (NEW)
    │   ├── OAuth2ConfigKeys.java                (NEW)
    │   ├── OAuth2TokenValidator.java            (NEW)
    │   ├── JwtClaims.java                        (NEW)
    │   └── TenantContextHolder.java              (NEW)
    ├── authz/
    │   ├── RangerAuthorizationFilter.java        (NEW)
    │   ├── S3ActionMapper.java                   (NEW)
    │   ├── AuthorizationRequest.java             (NEW)
    │   └── AuthorizationResponse.java            (NEW)
    ├── endpoints/
    │   ├── TenantEndpoint.java                   (MODIFY)
    │   ├── PolicyEndpoint.java                   (NEW)
    │   ├── RoleEndpoint.java                     (NEW)
    │   ├── UserEndpoint.java                     (NEW)
    │   └── AccessKeyEndpoint.java                (NEW)
    ├── model/
    │   ├── TenantCreateRequest.java              (NEW)
    │   ├── TenantResponse.java                   (NEW)
    │   ├── PolicyRequest.java                    (NEW)
    │   ├── RoleRequest.java                      (NEW)
    │   └── UserAssignmentRequest.java            (NEW)
    └── GatewayApplication.java                   (MODIFY: register filters)
```

### 4.2 Implementation Details

#### 4.2.1 OAuth2Filter.java


```java
package org.apache.ozone.admin.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;

/**
 * OAuth2 filter for Admin Gateway REST API.
 * Validates JWT tokens from Authorization: Bearer header.
 */
@Provider
@PreMatching
@Priority(10)  // Run before other filters
public class OAuth2Filter implements ContainerRequestFilter {
  
  private static final Logger LOG = LoggerFactory.getLogger(OAuth2Filter.class);
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  
  @Inject
  private OAuth2TokenValidator tokenValidator;
  
  @Inject
  private OzoneConfiguration conf;
  
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    // Skip authentication for health check endpoints
    if (requestContext.getUriInfo().getPath().startsWith("/health")) {
      return;
    }
    
    String authHeader = requestContext.getHeaderString(AUTHORIZATION_HEADER);
    
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      LOG.warn("Missing or invalid Authorization header");
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity("Missing or invalid Authorization header")
              .build()
      );
      return;
    }
    
    String token = authHeader.substring(BEARER_PREFIX.length());
    
    try {
      // Validate and parse JWT
      SignedJWT signedJWT = SignedJWT.parse(token);
      
      // Verify signature
      if (!tokenValidator.validateSignature(signedJWT)) {
        throw new SecurityException("Invalid token signature");
      }
      
      JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
      
      // Check expiration
      Date exp = claims.getExpirationTime();
      if (exp == null || exp.before(new Date())) {
        throw new SecurityException("Token expired");
      }
      
      // Extract user principal
      String subject = claims.getSubject();
      String email = claims.getStringClaim("email");
      
      // Store in thread-local context
      TenantContextHolder.setUser(subject != null ? subject : email);
      
      // Check required scopes
      String[] scopes = claims.getStringClaim("scope").split(" ");
      requestContext.setProperty("oauth2.scopes", scopes);
      requestContext.setProperty("oauth2.subject", subject);
      
      LOG.debug("Authenticated user: {}", subject);
      
    } catch (Exception e) {
      LOG.error("Token validation failed", e);
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity("Invalid or expired token: " + e.getMessage())
              .build()
      );
    }
  }
}
```

#### 4.2.2 OAuth2TokenValidator.java

```java
package org.apache.ozone.admin.auth;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates OAuth2 JWT tokens against OIDC provider.
 * Caches public keys from JWKS endpoint.
 */
@Singleton
public class OAuth2TokenValidator {
  
  private static final Logger LOG = LoggerFactory.getLogger(OAuth2TokenValidator.class);
  private final Map<String, RSAPublicKey> keyCache = new ConcurrentHashMap<>();
  private final String jwksUrl;
  private final String issuer;
  
  @Inject
  public OAuth2TokenValidator(OzoneConfiguration conf) {
    this.issuer = conf.get(OAuth2ConfigKeys.OZONE_ADMIN_OAUTH2_ISSUER);
    this.jwksUrl = conf.get(OAuth2ConfigKeys.OZONE_ADMIN_OAUTH2_JWKS_URL);
    
    if (issuer == null || jwksUrl == null) {
      throw new IllegalArgumentException(
          "OAuth2 configuration incomplete: issuer and jwksUrl required");
    }
    
    LOG.info("Initialized OAuth2 validator for issuer: {}", issuer);
  }
  
  public boolean validateSignature(SignedJWT signedJWT) throws Exception {
    String keyId = signedJWT.getHeader().getKeyID();
    
    // Get public key (from cache or fetch from JWKS)
    RSAPublicKey publicKey = keyCache.computeIfAbsent(keyId, k -> {
      try {
        return fetchPublicKey(k);
      } catch (Exception e) {
        LOG.error("Failed to fetch public key for kid: {}", k, e);
        return null;
      }
    });
    
    if (publicKey == null) {
      throw new SecurityException("Public key not found for kid: " + keyId);
    }
    
    JWSVerifier verifier = new RSASSAVerifier(publicKey);
    return signedJWT.verify(verifier);
  }
  
  private RSAPublicKey fetchPublicKey(String keyId) throws Exception {
    // Fetch JWKS from identity provider
    // Implementation depends on specific IdP (Keycloak, Okta, etc.)
    URL url = new URL(jwksUrl);
    // Parse JWK Set and extract public key for keyId
    // ... (implementation details)
    return null; // Placeholder
  }
}
```

#### 4.2.3 TenantEndpoint.java (Enhanced)

```java
package org.apache.ozone.admin.endpoints;

import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.ozone.admin.EndpointBase;
import org.apache.ozone.admin.auth.TenantContextHolder;
import org.apache.ozone.admin.model.TenantCreateRequest;
import org.apache.ozone.admin.model.TenantResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

/**
 * REST API for tenant management.
 * 
 * Endpoints:
 *   POST   /api/v1/tenants - Create tenant
 *   GET    /api/v1/tenants - List tenants
 *   GET    /api/v1/tenants/{id} - Get tenant details
 *   DELETE /api/v1/tenants/{id} - Delete tenant
 */
@Path("/api/v1/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantEndpoint extends EndpointBase {
  
  private static final Logger LOG = LoggerFactory.getLogger(TenantEndpoint.class);
  
  @Inject
  private ObjectStore objectStore;
  
  /**
   * Create a new tenant with Ranger policies.
   * 
   * Request body:
   * {
   *   "tenantId": "acme-corp",
   *   "adminUsers": ["alice@acme.com"],
   *   "volumeName": "acme-vol"
   * }
   */
  @POST
  public Response createTenant(TenantCreateRequest request) {
    String currentUser = TenantContextHolder.getUser();
    LOG.info("User {} creating tenant: {}", currentUser, request.getTenantId());
    
    try {
      // Validate request
      if (request.getTenantId() == null || request.getTenantId().isEmpty()) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("tenantId is required")
            .build();
      }
      
      // Create tenant (this triggers Ranger policy creation via OMMultiTenantManager)
      objectStore.createTenant(request.getTenantId());
      
      // Assign admin users
      for (String adminUser : request.getAdminUsers()) {
        objectStore.assignUserToTenant(adminUser, request.getTenantId(), /*isAdmin=*/true);
      }
      
      TenantResponse response = new TenantResponse();
      response.setTenantId(request.getTenantId());
      response.setStatus("ACTIVE");
      response.setCreatedBy(currentUser);
      
      return Response.status(Response.Status.CREATED)
          .entity(response)
          .build();
          
    } catch (IOException e) {
      LOG.error("Failed to create tenant: {}", request.getTenantId(), e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("Failed to create tenant: " + e.getMessage())
          .build();
    }
  }
  
  /**
   * List all tenants (admin only).
   */
  @GET
  public Response listTenants(@QueryParam("prefix") String prefix) {
    try {
      // Implementation using objectStore.listTenants()
      return Response.ok().entity("{}").build();
    } catch (Exception e) {
      return Response.status(500).entity(e.getMessage()).build();
    }
  }
  
  /**
   * Get tenant details.
   */
  @GET
  @Path("/{tenantId}")
  public Response getTenant(@PathParam("tenantId") String tenantId) {
    try {
      // Implementation
      return Response.ok().entity("{}").build();
    } catch (Exception e) {
      return Response.status(500).entity(e.getMessage()).build();
    }
  }
  
  /**
   * Delete tenant (removes Ranger policies and roles).
   */
  @DELETE
  @Path("/{tenantId}")
  public Response deleteTenant(@PathParam("tenantId") String tenantId) {
    LOG.info("Deleting tenant: {}", tenantId);
    
    try {
      objectStore.deleteTenant(tenantId);
      return Response.status(Response.Status.NO_CONTENT).build();
    } catch (IOException e) {
      LOG.error("Failed to delete tenant: {}", tenantId, e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("Failed to delete tenant: " + e.getMessage())
          .build();
    }
  }
}
```

#### 4.2.4 PolicyEndpoint.java

```java
package org.apache.ozone.admin.endpoints;

import org.apache.ozone.admin.model.PolicyRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * REST API for managing Ranger policies.
 * 
 * Endpoints:
 *   POST   /api/v1/policies - Create policy
 *   GET    /api/v1/policies - List policies
 *   GET    /api/v1/policies/{id} - Get policy
 *   PUT    /api/v1/policies/{id} - Update policy
 *   DELETE /api/v1/policies/{id} - Delete policy
 */
@Path("/api/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PolicyEndpoint {
  
  private static final Logger LOG = LoggerFactory.getLogger(PolicyEndpoint.class);
  
  @POST
  public Response createPolicy(PolicyRequest request) {
    // Create custom Ranger policy
    // Example: Allow user "bob" to read bucket "shared-data" in tenant "acme-corp"
    return Response.status(201).entity("{}").build();
  }
  
  @GET
  public Response listPolicies(
      @QueryParam("tenantId") String tenantId,
      @QueryParam("resource") String resource) {
    // List policies for tenant/resource
    return Response.ok().entity("[]").build();
  }
}
```

### 4.3 Ranger Policy Modeling

#### 4.3.1 Policy Structure

Ranger policies for Ozone multi-tenancy follow this hierarchy:

```json
{
  "policyType": "access",
  "name": "tenant-acme-corp-volume-policy",
  "service": "ozone-service",
  "resources": {
    "volume": {
      "values": ["acme-vol"],
      "isExcludes": false,
      "isRecursive": false
    }
  },
  "policyItems": [
    {
      "accesses": [
        {"type": "list", "isAllowed": true}
      ],
      "users": [],
      "groups": [],
      "roles": ["acme-corp-UserRole", "acme-corp-AdminRole"],
      "delegateAdmin": false
    }
  ]
}
```

#### 4.3.2 Tenant Volume Policy

Created automatically when tenant is created:

```json
{
  "name": "tenant-{tenantId}-volume-access",
  "policyType": "access",
  "service": "ozone-service",
  "resources": {
    "volume": {
      "values": ["{tenantVolume}"],
      "isExcludes": false
    }
  },
  "policyItems": [
    {
      "accesses": [
        {"type": "list", "isAllowed": true},
        {"type": "read", "isAllowed": true}
      ],
      "roles": ["{tenantId}-UserRole", "{tenantId}-AdminRole"]
    }
  ]
}
```

#### 4.3.3 Bucket Creation Policy

Allows all tenant users to create buckets:

```json
{
  "name": "tenant-{tenantId}-bucket-create",
  "policyType": "access",
  "service": "ozone-service",
  "resources": {
    "volume": {"values": ["{tenantVolume}"]},
    "bucket": {"values": ["*"], "isRecursive": false}
  },
  "policyItems": [
    {
      "accesses": [
        {"type": "create", "isAllowed": true},
        {"type": "list", "isAllowed": true}
      ],
      "roles": ["{tenantId}-UserRole"]
    }
  ]
}
```

#### 4.3.4 Bucket Owner Policy

Automatically created when bucket is created, grants full access to owner:

```json
{
  "name": "bucket-{bucketName}-owner-policy",
  "policyType": "access",
  "service": "ozone-service",
  "resources": {
    "volume": {"values": ["{tenantVolume}"]},
    "bucket": {"values": ["{bucketName}"]},
    "key": {"values": ["*"], "isRecursive": true}
  },
  "policyItems": [
    {
      "accesses": [
        {"type": "read", "isAllowed": true},
        {"type": "write", "isAllowed": true},
        {"type": "create", "isAllowed": true},
        {"type": "list", "isAllowed": true},
        {"type": "delete", "isAllowed": true},
        {"type": "read_acl", "isAllowed": true},
        {"type": "write_acl", "isAllowed": true}
      ],
      "users": ["{OWNER}"],  // Special tag resolved at runtime
      "delegateAdmin": true
    }
  ]
}
```

#### 4.3.5 Shared Bucket Policy (Cross-Tenant)

Manual policy for sharing bucket across tenants:

```json
{
  "name": "shared-bucket-ml-datasets-policy",
  "policyType": "access",
  "service": "ozone-service",
  "resources": {
    "volume": {"values": ["acme-vol"]},
    "bucket": {"values": ["ml-datasets"]},
    "key": {"values": ["public/*"], "isRecursive": true}
  },
  "policyItems": [
    {
      "accesses": [
        {"type": "read", "isAllowed": true},
        {"type": "list", "isAllowed": true}
      ],
      "roles": ["tenant-b-UserRole", "tenant-c-UserRole"],
      "delegateAdmin": false
    }
  ]
}
```

#### 4.3.6 Prefix-Based Policy

Grant access to specific object prefixes:

```json
{
  "name": "bucket-logs-prefix-policy",
  "policyType": "access",
  "service": "ozone-service",
  "resources": {
    "volume": {"values": ["acme-vol"]},
    "bucket": {"values": ["logs"]},
    "key": {"values": ["user-{USER}/*"], "isRecursive": true}
  },
  "policyItems": [
    {
      "accesses": [
        {"type": "read", "isAllowed": true},
        {"type": "write", "isAllowed": true}
      ],
      "users": ["{USER}"],  // Special tag: each user sees own prefix
      "delegateAdmin": false
    }
  ]
}
```

### 4.4 S3 Operation to Ranger Permission Mapping

Implement `S3ActionMapper.java`:

```java
package org.apache.ozone.admin.authz;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class S3ActionMapper {
  
  private static final Map<String, Set<String>> S3_TO_RANGER_PERMS = new HashMap<>();
  
  static {
    // Bucket operations
    S3_TO_RANGER_PERMS.put("s3:ListBucket", Set.of("list", "read"));
    S3_TO_RANGER_PERMS.put("s3:CreateBucket", Set.of("create"));
    S3_TO_RANGER_PERMS.put("s3:DeleteBucket", Set.of("delete"));
    S3_TO_RANGER_PERMS.put("s3:GetBucketAcl", Set.of("read_acl"));
    S3_TO_RANGER_PERMS.put("s3:PutBucketAcl", Set.of("write_acl"));
    
    // Object operations
    S3_TO_RANGER_PERMS.put("s3:GetObject", Set.of("read"));
    S3_TO_RANGER_PERMS.put("s3:PutObject", Set.of("write", "create"));
    S3_TO_RANGER_PERMS.put("s3:DeleteObject", Set.of("delete"));
    S3_TO_RANGER_PERMS.put("s3:GetObjectAcl", Set.of("read_acl"));
    S3_TO_RANGER_PERMS.put("s3:PutObjectAcl", Set.of("write_acl"));
    
    // Multipart upload
    S3_TO_RANGER_PERMS.put("s3:PutObjectPart", Set.of("write"));
    S3_TO_RANGER_PERMS.put("s3:AbortMultipartUpload", Set.of("write"));
  }
  
  public static Set<String> mapS3ActionToRangerPerms(String s3Action) {
    return S3_TO_RANGER_PERMS.getOrDefault(s3Action, Set.of());
  }
}
```

### 4.5 Configuration

#### 4.5.1 ozone-site.xml

```xml
<configuration>
  <!-- OAuth2 Configuration for Admin Gateway -->
  <property>
    <name>ozone.admin.gateway.oauth2.enabled</name>
    <value>true</value>
    <description>Enable OAuth2 authentication for Admin Gateway REST API</description>
  </property>
  
  <property>
    <name>ozone.admin.gateway.oauth2.issuer</name>
    <value>https://keycloak.example.com/realms/ozone</value>
    <description>OAuth2 issuer URL (OIDC provider)</description>
  </property>
  
  <property>
    <name>ozone.admin.gateway.oauth2.jwks.url</name>
    <value>https://keycloak.example.com/realms/ozone/protocol/openid-connect/certs</value>
    <description>JWKS endpoint for JWT signature verification</description>
  </property>
  
  <property>
    <name>ozone.admin.gateway.oauth2.audience</name>
    <value>ozone-admin-api</value>
    <description>Expected audience in JWT tokens</description>
  </property>
  
  <!-- Multi-Tenancy Configuration -->
  <property>
    <name>ozone.om.multitenancy.enabled</name>
    <value>true</value>
    <description>Enable S3 multi-tenancy support</description>
  </property>
  
  <property>
    <name>ozone.om.multitenancy.ranger.sync.interval</name>
    <value>300s</value>
    <description>Ranger policy/role sync interval</description>
  </property>
  
  <!-- Ranger Plugin Configuration -->
  <property>
    <name>ozone.om.ranger.service.name</name>
    <value>ozone-production</value>
    <description>Ranger service name for Ozone</description>
  </property>
  
  <property>
    <name>ozone.om.ranger.https.enabled</name>
    <value>true</value>
    <description>Use HTTPS for Ranger communication</description>
  </property>
  
  <!-- Admin Gateway HTTP Server -->
  <property>
    <name>ozone.admin.gateway.http.enabled</name>
    <value>true</value>
  </property>
  
  <property>
    <name>ozone.admin.gateway.http.bind.host</name>
    <value>0.0.0.0</value>
  </property>
  
  <property>
    <name>ozone.admin.gateway.http.bind.port</name>
    <value>9880</value>
  </property>
  
  <!-- Audit Configuration -->
  <property>
    <name>ozone.om.ranger.audit.destination</name>
    <value>solr,hdfs</value>
    <description>Ranger audit destinations (solr, hdfs, log, kafka)</description>
  </property>
</configuration>
```

#### 4.5.2 ranger-ozone-security.xml

```xml
<configuration>
  <property>
    <name>ranger.plugin.ozone.service.name</name>
    <value>ozone-production</value>
  </property>
  
  <property>
    <name>ranger.plugin.ozone.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
  </property>
  
  <property>
    <name>ranger.plugin.ozone.policy.rest.url</name>
    <value>https://ranger-admin.example.com:6182</value>
  </property>
  
  <property>
    <name>ranger.plugin.ozone.policy.rest.ssl.config.file</name>
    <value>/etc/ozone/conf/ranger-policymgr-ssl.xml</value>
  </property>
  
  <property>
    <name>ranger.plugin.ozone.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Policy refresh interval (30 seconds)</description>
  </property>
  
  <property>
    <name>ranger.plugin.ozone.policy.cache.dir</name>
    <value>/var/ozone/ranger/cache</value>
  </property>
</configuration>
```

#### 4.5.3 ranger-ozone-audit.xml

```xml
<configuration>
  <!-- Solr Audit -->
  <property>
    <name>xasecure.audit.destination.solr</name>
    <value>true</value>
  </property>
  
  <property>
    <name>xasecure.audit.destination.solr.urls</name>
    <value>http://solr1.example.com:8983/solr/ranger_audits,http://solr2.example.com:8983/solr/ranger_audits</value>
  </property>
  
  <!-- HDFS Audit -->
  <property>
    <name>xasecure.audit.destination.hdfs</name>
    <value>true</value>
  </property>
  
  <property>
    <name>xasecure.audit.destination.hdfs.dir</name>
    <value>hdfs://namenode.example.com:9000/ranger/audit/ozone</value>
  </property>
  
  <property>
    <name>xasecure.audit.destination.hdfs.batch.filespool.dir</name>
    <value>/var/log/ozone/ranger/audit/hdfs/spool</value>
  </property>
  
  <!-- Async Audit Queue -->
  <property>
    <name>xasecure.audit.provider.async.queue.size</name>
    <value>10000</value>
  </property>
  
  <property>
    <name>xasecure.audit.provider.async.batch.size</name>
    <value>1000</value>
  </property>
</configuration>
```

### 4.6 Build Instructions

#### 4.6.1 Maven Build Commands

```bash
# Navigate to Ozone root directory
cd /Users/lixucheng/Documents/oss/apache/ozone

# Build the entire project (initial build)
mvn clean install -DskipTests

# Build only Admin Gateway module with dependencies
mvn clean install -pl hadoop-ozone/admin-gateway -am -DskipTests

# Build with Ranger profile enabled
mvn clean install -Pranger -pl hadoop-ozone/ozone-manager -am -DskipTests

# Run unit tests for Admin Gateway
mvn test -pl hadoop-ozone/admin-gateway

# Run integration tests for multi-tenancy
mvn verify -pl hadoop-ozone/integration-test -Dit.test=TestMultiTenancy*

# Build distribution package
mvn clean package -DskipTests -Pdist

# Quick rebuild after code changes (skips checks)
mvn clean install -pl hadoop-ozone/admin-gateway -am -DskipTests -Dcheckstyle.skip=true -Dfindbugs.skip=true
```

#### 4.6.2 Dependencies to Add in `admin-gateway/pom.xml`

```xml
<dependencies>
  <!-- OAuth2 / JWT -->
  <dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>9.31</version>
  </dependency>
  
  <!-- JAX-RS / Jersey -->
  <dependency>
    <groupId>org.glassfish.jersey.core</groupId>
    <artifactId>jersey-server</artifactId>
  </dependency>
  
  <dependency>
    <groupId>org.glassfish.jersey.inject</groupId>
    <artifactId>jersey-hk2</artifactId>
  </dependency>
  
  <!-- JSON Processing -->
  <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
  </dependency>
  
  <!-- Ozone Client -->
  <dependency>
    <groupId>org.apache.ozone</groupId>
    <artifactId>hdds-client</artifactId>
  </dependency>
  
  <dependency>
    <groupId>org.apache.ozone</groupId>
    <artifactId>ozone-client</artifactId>
  </dependency>
  
  <!-- Testing -->
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-api</artifactId>
    <scope>test</scope>
  </dependency>
  
  <dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

### 4.7 Local Development Setup

#### Step 1: Start Ranger Admin Server

```bash
# Pull Ranger Docker image
docker pull apache/ranger:latest

# Run Ranger Admin
docker run -d \
  --name ranger-admin \
  -p 6080:6080 \
  -e MYSQL_ROOT_PASSWORD=rangerpass \
  apache/ranger:latest

# Wait for Ranger to start (check logs)
docker logs -f ranger-admin

# Access Ranger UI: http://localhost:6080
# Default credentials: admin/admin
```

#### Step 2: Configure Ranger Ozone Service

1. Login to Ranger UI: http://localhost:6080 (admin/admin)
2. Click "+" next to "OZONE" services
3. Create service:
   - Service Name: `ozone-local`
   - Display Name: `Ozone Local Dev`
   - Active Status: Enabled
   - Config: ozone.om.address=localhost:9862
4. Save

#### Step 3: Start Ozone Cluster with Multi-Tenancy

```bash
# Set environment
export OZONE_HOME=/Users/lixucheng/Documents/oss/apache/ozone
export OZONE_CONF_DIR=$OZONE_HOME/hadoop-ozone/dist/target/ozone-*/etc/hadoop

# Configure ozone-site.xml
cat > $OZONE_CONF_DIR/ozone-site.xml << 'EOF'
<configuration>
  <property>
    <name>ozone.om.multitenancy.enabled</name>
    <value>true</value>
  </property>
  <property>
    <name>ozone.om.ranger.service.name</name>
    <value>ozone-local</value>
  </property>
  <property>
    <name>ranger.plugin.ozone.policy.rest.url</name>
    <value>http://localhost:6080</value>
  </property>
</configuration>
EOF

# Start Ozone (single-node cluster)
cd $OZONE_HOME
./hadoop-ozone/dist/target/ozone-*/bin/ozone scm --init
./hadoop-ozone/dist/target/ozone-*/bin/ozone scm &
./hadoop-ozone/dist/target/ozone-*/bin/ozone om --init
./hadoop-ozone/dist/target/ozone-*/bin/ozone om &
./hadoop-ozone/dist/target/ozone-*/bin/ozone datanode &
```

#### Step 4: Start Admin Gateway

```bash
# Start Admin Gateway
./hadoop-ozone/dist/target/ozone-*/bin/ozone ag &

# Verify it's running
curl http://localhost:9880/health
```

#### Step 5: Configure Keycloak (OAuth2 Provider)

```bash
# Start Keycloak
docker run -d \
  --name keycloak \
  -p 8080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:latest start-dev

# Access: http://localhost:8080
# Create realm: "ozone"
# Create client: "ozone-admin-api"
# Client settings:
#   - Access Type: bearer-only
#   - Standard Flow: Enabled
#   - Valid Redirect URIs: http://localhost:9880/*

# Create users:
#   - alice@example.com (tenant admin)
#   - bob@example.com (regular user)
```

#### Step 6: Test Tenant Creation

```bash
# Get OAuth2 token from Keycloak
TOKEN=$(curl -X POST http://localhost:8080/realms/ozone/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=ozone-admin-api" \
  -d "username=alice@example.com" \
  -d "password=password" \
  | jq -r .access_token)

# Create tenant via Admin Gateway API
curl -X POST http://localhost:9880/api/v1/tenants \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "test-tenant",
    "adminUsers": ["alice@example.com"],
    "volumeName": "test-vol"
  }'

# Verify Ranger policies were created
# Login to Ranger UI → OZONE service → Policies
# Should see:
#   - tenant-test-tenant-volume-access
#   - tenant-test-tenant-bucket-create
```

### 4.8 Test Cases

#### 4.8.1 Unit Test: OAuth2 Token Validation

```java
package org.apache.ozone.admin.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class OAuth2TokenValidatorTest {
  
  private KeyPair keyPair;
  private OAuth2TokenValidator validator;
  
  @BeforeEach
  void setUp() throws Exception {
    // Generate test RSA key pair
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    keyPair = gen.generateKeyPair();
    
    // Mock validator (inject test public key)
    validator = new OAuth2TokenValidator(/* test config */);
  }
  
  @Test
  void testValidTokenAccepted() throws Exception {
    // Create valid JWT
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject("alice@example.com")
        .issuer("http://localhost:8080/realms/ozone")
        .expirationTime(new Date(System.currentTimeMillis() + 3600000))
        .build();
    
    SignedJWT jwt = new SignedJWT(
        new JWSHeader(JWSAlgorithm.RS256),
        claims
    );
    jwt.sign(new RSASSASigner(keyPair.getPrivate()));
    
    // Should validate successfully
    assertTrue(validator.validateSignature(jwt));
  }
  
  @Test
  void testExpiredTokenRejected() throws Exception {
    // Create expired JWT
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject("alice@example.com")
        .issuer("http://localhost:8080/realms/ozone")
        .expirationTime(new Date(System.currentTimeMillis() - 3600000)) // Expired
        .build();
    
    SignedJWT jwt = new SignedJWT(
        new JWSHeader(JWSAlgorithm.RS256),
        claims
    );
    jwt.sign(new RSASSASigner(keyPair.getPrivate()));
    
    // Should reject
    assertThrows(SecurityException.class, () -> {
      validator.validateSignature(jwt);
    });
  }
}
```

#### 4.8.2 Integration Test: Tenant Creation with Ranger Policies

```java
package org.apache.ozone.admin.integration;

import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.om.OMMultiTenantManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantCreationIntegrationTest {
  
  @Mock
  private OMMultiTenantManager tenantManager;
  
  @Test
  void testTenantCreationCreatesRangerPolicies() throws Exception {
    ObjectStore store = mock(ObjectStore.class);
    
    // Create tenant
    String tenantId = "acme-corp";
    store.createTenant(tenantId);
    
    // Verify Ranger policy creation calls
    verify(tenantManager).getAuthorizerOp();
    // Verify roles created: acme-corp-UserRole, acme-corp-AdminRole
    // Verify policies created: volume policy, bucket policy
  }
  
  @Test
  void testUnauthorizedUserCannotCreateTenant() {
    // Attempt to create tenant without admin privileges
    // Should throw OMException with PERMISSION_DENIED
  }
  
  @Test
  void testMultiTenantIsolation() throws Exception {
    // Create two tenants
    String tenant1 = "tenant-a";
    String tenant2 = "tenant-b";
    
    // Assign user to tenant-a
    // Verify user cannot access tenant-b resources
  }
}
```

---

## 5. Migration to SpiceDB

### 5.1 SpiceDB Overview

SpiceDB is a Google Zanzibar-inspired authorization system that provides:
- **Relationship-Based Access Control (ReBAC)**: Model complex permission relationships
- **Horizontal Scalability**: Distributed architecture for high throughput
- **Consistency Guarantees**: ZedTokens for point-in-time consistency
- **Flexible Schema**: Adapt to evolving permission models

### 5.2 SpiceDB Schema Design

#### 5.2.1 Complete Schema Definition

```zed
// admin-gateway-authorization-proposal.md

/** 
 * User represents an identity (human or service account).
 */
definition user {}

/**
 * Tenant represents an isolated multi-tenant namespace.
 * Maps to an Ozone volume.
 */
definition tenant {
  // Relations
  relation admin: user
  relation member: user
  relation owner: user
  
  // Permissions
  permission manage = admin + owner
  permission access = member + admin + owner
  permission delegate = admin
}

/**
 * Volume represents an Ozone volume.
 * Each tenant has one primary volume.
 */
definition volume {
  relation tenant: tenant
  relation owner: user
  
  permission read = tenant->access
  permission write = tenant->manage + owner
  permission admin = tenant->manage
}

/**
 * Bucket represents an S3 bucket within a volume.
 */
definition bucket {
  relation volume: volume
  relation owner: user
  relation viewer: user | user:*
  relation editor: user
  relation admin: user
  
  // Computed permissions
  permission read = viewer + editor + admin + owner + volume->read
  permission write = editor + admin + owner + volume->write
  permission delete = admin + owner + volume->admin
  permission read_acl = admin + owner
  permission write_acl = admin + owner
  permission list = read
}

/**
 * Object represents an S3 object (key) within a bucket.
 */
definition object {
  relation bucket: bucket
  relation owner: user
  relation viewer: user | user:*
  relation editor: user
  
  // Inherit bucket permissions
  permission read = viewer + editor + owner + bucket->read
  permission write = editor + owner + bucket->write
  permission delete = owner + bucket->delete
}

/**
 * Folder represents a prefix/path within a bucket.
 * Enables prefix-based access control.
 */
definition folder {
  relation bucket: bucket
  relation parent: folder
  relation viewer: user
  relation editor: user
  
  permission read = viewer + editor + bucket->read + parent->read
  permission write = editor + bucket->write + parent->write
  permission list = read
}

/**
 * Role is a named collection of permissions.
 * Can be assigned to users for a specific resource.
 */
definition role {
  relation assignee: user
  relation granted_on: tenant | volume | bucket
  
  permission use = assignee
}
```

#### 5.2.2 Relationship Tuple Examples

```python
# Tenant setup
tenant:acme-corp#owner@user:admin
tenant:acme-corp#admin@user:alice
tenant:acme-corp#member@user:bob
tenant:acme-corp#member@user:charlie

# Volume relationship
volume:acme-vol#tenant@tenant:acme-corp
volume:acme-vol#owner@user:alice

# Bucket relationships
bucket:ml-datasets#volume@volume:acme-vol
bucket:ml-datasets#owner@user:bob
bucket:ml-datasets#editor@user:charlie
bucket:ml-datasets#viewer@user:*  # Public read

# Object relationships
object:acme-vol/ml-datasets/train.csv#bucket@bucket:ml-datasets
object:acme-vol/ml-datasets/train.csv#owner@user:bob

# Folder (prefix) relationships
folder:acme-vol/logs/app1#bucket@bucket:logs
folder:acme-vol/logs/app1#editor@user:service-account-app1
folder:acme-vol/logs/app1/debug#parent@folder:acme-vol/logs/app1
folder:acme-vol/logs/app1/debug#viewer@user:alice

# Time-bound access (using caveats)
bucket:temp-data#editor@user:bob[expiry:2025-12-31]
```

#### 5.2.3 Permission Check Examples

```bash
# Check if bob can read bucket ml-datasets
spicedb check \
  --subject user:bob \
  --permission read \
  --resource bucket:ml-datasets

# Check if charlie can write object
spicedb check \
  --subject user:charlie \
  --permission write \
  --resource object:acme-vol/ml-datasets/train.csv

# List all resources user alice can read
spicedb expand \
  --subject user:alice \
  --permission read \
  --resource-type bucket
```

### 5.3 SpiceDB Integration Architecture

```java
package org.apache.hadoop.ozone.om.multitenant;

import com.authzed.api.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * SpiceDB-based implementation of MultiTenantAccessController.
 * Replaces Ranger for authorization decisions.
 */
public class SpiceDBAccessController implements MultiTenantAccessController {
  
  private final PermissionsServiceGrpc.PermissionsServiceBlockingStub client;
  private final String prefix; // e.g., "ozone/"
  
  public SpiceDBAccessController(String endpoint, String token) {
    ManagedChannel channel = ManagedChannelBuilder
        .forTarget(endpoint)
        .useTransportSecurity()
        .build();
    
    this.client = PermissionsServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new BearerToken(token));
    this.prefix = "ozone/";
  }
  
  @Override
  public boolean checkPermission(
      String principal,
      String resource,
      String permission) {
    
    // Build CheckPermissionRequest
    CheckPermissionRequest request = CheckPermissionRequest.newBuilder()
        .setConsistency(
            Consistency.newBuilder()
                .setFullyConsistent(true)
                .build()
        )
        .setSubject(
            SubjectReference.newBuilder()
                .setObject(
                    ObjectReference.newBuilder()
                        .setObjectType("user")
                        .setObjectId(principal)
                        .build()
                )
                .build()
        )
        .setPermission(permission)
        .setResource(parseResource(resource))
        .build();
    
    // Execute check
    CheckPermissionResponse response = client.checkPermission(request);
    
    return response.getPermissionship() == 
        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION;
  }
  
  @Override
  public void writeRelationship(
      String resourceType,
      String resourceId,
      String relation,
      String subjectType,
      String subjectId) {
    
    Relationship relationship = Relationship.newBuilder()
        .setResource(
            ObjectReference.newBuilder()
                .setObjectType(resourceType)
                .setObjectId(resourceId)
                .build()
        )
        .setRelation(relation)
        .setSubject(
            SubjectReference.newBuilder()
                .setObject(
                    ObjectReference.newBuilder()
                        .setObjectType(subjectType)
                        .setObjectId(subjectId)
                        .build()
                )
                .build()
        )
        .build();
    
    WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
        .addUpdates(
            RelationshipUpdate.newBuilder()
                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                .setRelationship(relationship)
                .build()
        )
        .build();
    
    client.writeRelationships(request);
  }
  
  private ObjectReference parseResource(String resource) {
    // Parse resource string: "bucket:ml-datasets" or "object:vol/bucket/key"
    String[] parts = resource.split(":", 2);
    return ObjectReference.newBuilder()
        .setObjectType(parts[0])
        .setObjectId(parts[1])
        .build();
  }
}
```

### 5.4 Data Migration Strategy

#### 5.4.1 Phase 1: Dual-Write (Weeks 1-2)

**Goal**: Write to both Ranger and SpiceDB simultaneously

```java
public class DualWriteAccessController implements MultiTenantAccessController {
  
  private final RangerAccessController ranger;
  private final SpiceDBAccessController spicedb;
  private final MetricsRegistry metrics;
  
  @Override
  public void createTenantPolicy(String tenantId, Policy policy) throws IOException {
    // Write to Ranger (primary)
    try {
      ranger.createTenantPolicy(tenantId, policy);
    } catch (Exception e) {
      LOG.error("Ranger write failed", e);
      metrics.increment("dual_write.ranger.error");
      throw e;
    }
    
    // Write to SpiceDB (secondary, best-effort)
    CompletableFuture.runAsync(() -> {
      try {
        spicedb.writeRelationship(
            "tenant", tenantId,
            "member", "user", policy.getUser()
        );
        metrics.increment("dual_write.spicedb.success");
      } catch (Exception e) {
        LOG.warn("SpiceDB write failed (non-fatal)", e);
        metrics.increment("dual_write.spicedb.error");
      }
    });
  }
}
```

**Migration Script**: Backfill existing Ranger data to SpiceDB

```python
#!/usr/bin/env python3
"""
Migrate existing Ranger policies to SpiceDB relationships.
"""

import requests
from authzed.api.v1 import Client

# Connect to Ranger
ranger_url = "http://ranger-admin:6080"
ranger_auth = ("admin", "admin")

# Connect to SpiceDB
spicedb_client = Client(
    endpoint="spicedb.example.com:50051",
    bearer_token="sdb_token_xyz"
)

def fetch_ranger_policies():
    """Fetch all Ozone policies from Ranger."""
    resp = requests.get(
        f"{ranger_url}/service/public/v2/api/service/ozone-production/policy",
        auth=ranger_auth
    )
    return resp.json()

def map_policy_to_relationships(policy):
    """Convert Ranger policy to SpiceDB relationships."""
    relationships = []
    
    # Extract resource
    resources = policy["resources"]
    volume = resources.get("volume", {}).get("values", [])[0]
    bucket = resources.get("bucket", {}).get("values", [None])[0]
    
    # Extract permissions
    for item in policy["policyItems"]:
        for user in item.get("users", []):
            for access in item["accesses"]:
                if access["isAllowed"]:
                    perm = access["type"]  # read, write, etc.
                    
                    # Map to SpiceDB relation
                    if perm == "read":
                        relation = "viewer"
                    elif perm == "write":
                        relation = "editor"
                    elif perm == "delete":
                        relation = "admin"
                    else:
                        continue
                    
                    relationships.append({
                        "resource_type": "bucket" if bucket else "volume",
                        "resource_id": f"{volume}/{bucket}" if bucket else volume,
                        "relation": relation,
                        "subject_type": "user",
                        "subject_id": user
                    })
    
    return relationships

def write_to_spicedb(relationships):
    """Write relationships to SpiceDB."""
    for rel in relationships:
        spicedb_client.WriteRelationships([
            {
                "resource": {
                    "object_type": rel["resource_type"],
                    "object_id": rel["resource_id"]
                },
                "relation": rel["relation"],
                "subject": {
                    "object": {
                        "object_type": rel["subject_type"],
                        "object_id": rel["subject_id"]
                    }
                }
            }
        ])

if __name__ == "__main__":
    print("Fetching Ranger policies...")
    policies = fetch_ranger_policies()
    print(f"Found {len(policies)} policies")
    
    for policy in policies:
        print(f"Migrating policy: {policy['name']}")
        relationships = map_policy_to_relationships(policy)
        write_to_spicedb(relationships)
    
    print("Migration complete")
```

#### 5.4.2 Phase 2: Dual-Read with Comparison (Weeks 3-4)

**Goal**: Read from both systems and compare results

```java
public class DualReadAccessController implements MultiTenantAccessController {
  
  private final RangerAccessController ranger;
  private final SpiceDBAccessController spicedb;
  private final MetricsRegistry metrics;
  
  @Override
  public boolean checkPermission(String principal, String resource, String permission) {
    // Read from both
    boolean rangerResult = ranger.checkPermission(principal, resource, permission);
    boolean spicedbResult = spicedb.checkPermission(principal, resource, permission);
    
    // Compare results
    if (rangerResult != spicedbResult) {
      LOG.warn("Authorization mismatch! Ranger={}, SpiceDB={}, principal={}, resource={}, perm={}",
          rangerResult, spicedbResult, principal, resource, permission);
      metrics.increment("dual_read.mismatch");
      
      // Log to audit for investigation
      auditLog.write(new MismatchEvent(principal, resource, permission, rangerResult, spicedbResult));
    } else {
      metrics.increment("dual_read.match");
    }
    
    // Return Ranger result (still primary)
    return rangerResult;
  }
}
```

**Monitoring Dashboard**:
```yaml
# Grafana dashboard config
- title: "SpiceDB Migration Health"
  panels:
    - name: "Dual-Write Success Rate"
      query: "rate(dual_write_spicedb_success[5m]) / rate(dual_write_ranger_success[5m])"
      target: "> 0.99"
    
    - name: "Dual-Read Mismatch Rate"
      query: "rate(dual_read_mismatch[5m]) / rate(dual_read_total[5m])"
      target: "< 0.001"  # Less than 0.1% mismatch
    
    - name: "SpiceDB Latency"
      query: "histogram_quantile(0.95, spicedb_check_duration_seconds)"
      target: "< 0.010"  # p95 < 10ms
```

#### 5.4.3 Phase 3: Cutover (Week 5)

**Goal**: Switch reads to SpiceDB, keep Ranger writes for rollback

```java
@Configuration
public class AuthorizationConfig {
  
  @Bean
  public MultiTenantAccessController accessController(
      FeatureFlags flags,
      RangerAccessController ranger,
      SpiceDBAccessController spicedb) {
    
    if (flags.isEnabled("spicedb.reads")) {
      LOG.info("SpiceDB read cutover enabled");
      return new CutoverAccessController(ranger, spicedb);
    } else {
      return new DualReadAccessController(ranger, spicedb);
    }
  }
}

public class CutoverAccessController implements MultiTenantAccessController {
  
  private final RangerAccessController ranger;
  private final SpiceDBAccessController spicedb;
  
  @Override
  public boolean checkPermission(String principal, String resource, String permission) {
    try {
      // Primary: SpiceDB
      return spicedb.checkPermission(principal, resource, permission);
    } catch (Exception e) {
      LOG.error("SpiceDB check failed, falling back to Ranger", e);
      metrics.increment("cutover.spicedb.failure");
      // Fallback: Ranger
      return ranger.checkPermission(principal, resource, permission);
    }
  }
  
  @Override
  public void createTenantPolicy(String tenantId, Policy policy) {
    // Still write to both for safety
    ranger.createTenantPolicy(tenantId, policy);
    spicedb.writeRelationship(...);
  }
}
```

**Cutover Checklist**:
```markdown
- [ ] Dual-read mismatch rate < 0.1% for 7 days
- [ ] SpiceDB p99 latency < 20ms
- [ ] SpiceDB availability > 99.9%
- [ ] All historical data migrated (verified via count check)
- [ ] Rollback runbook tested in staging
- [ ] On-call team briefed
- [ ] Feature flag ready: `spicedb.reads.enabled`
- [ ] Monitoring alerts configured
- [ ] Gradual rollout plan: 1% → 10% → 50% → 100%
```

**Gradual Rollout** (canary deployment):
```java
public class CanaryAccessController implements MultiTenantAccessController {
  
  private final RangerAccessController ranger;
  private final SpiceDBAccessController spicedb;
  private final double canaryPercent; // 0.0 to 1.0
  
  @Override
  public boolean checkPermission(String principal, String resource, String permission) {
    // Hash principal to determine routing
    double hash = Math.abs(principal.hashCode() % 1000) / 1000.0;
    
    if (hash < canaryPercent) {
      // Route to SpiceDB (canary)
      metrics.increment("canary.spicedb");
      return spicedb.checkPermission(principal, resource, permission);
    } else {
      // Route to Ranger (control)
      metrics.increment("canary.ranger");
      return ranger.checkPermission(principal, resource, permission);
    }
  }
}
```

#### 5.4.4 Phase 4: Cleanup (Week 6+)

**Goal**: Remove Ranger dependencies

1. **Stop Ranger writes** (keep reads for 1 week as safety net)
2. **Monitor error rates** (should be zero)
3. **Archive Ranger data**:
   ```bash
   # Export Ranger policies to JSON
   curl -u admin:admin \
     http://ranger-admin:6080/service/public/v2/api/service/ozone-production/policy \
     > ranger-policies-backup-2025-10-14.json
   ```
4. **Remove Ranger from code**:
   ```java
   // Delete RangerAccessController.java
   // Remove Ranger dependencies from pom.xml
   // Update config to use SpiceDB only
   ```
5. **Decommission Ranger service**
6. **Update documentation**

### 5.5 Rollback Plan

**Triggers for Rollback**:
- SpiceDB availability < 99%
- Error rate spike > 1%
- p99 latency > 100ms for 5 minutes
- Data consistency issues detected

**Rollback Procedure**:
```bash
# 1. Disable SpiceDB reads via feature flag
curl -X POST http://admin-gateway:9880/api/admin/feature-flags \
  -d '{"spicedb.reads.enabled": false}'

# 2. Verify traffic shifted back to Ranger
# Check metrics: ranger_check_total should increase

# 3. Investigate SpiceDB issues
kubectl logs -f spicedb-0 -n ozone

# 4. If needed, stop SpiceDB writes
# (keep Ranger as single source of truth)
```

### 5.6 Performance Optimization

#### 5.6.1 Caching Strategy

```java
public class CachedSpiceDBAccessController implements MultiTenantAccessController {
  
  private final SpiceDBAccessController spicedb;
  private final LoadingCache<PermissionKey, Boolean> cache;
  
  public CachedSpiceDBAccessController(SpiceDBAccessController spicedb) {
    this.spicedb = spicedb;
    this.cache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .recordStats()
        .build(key -> spicedb.checkPermission(
            key.getPrincipal(), key.getResource(), key.getPermission()));
  }
  
  @Override
  public boolean checkPermission(String principal, String resource, String permission) {
    PermissionKey key = new PermissionKey(principal, resource, permission);
    return cache.get(key);
  }
  
  // Invalidate cache on permission changes
  public void invalidate(String resource) {
    cache.invalidateAll(cache.asMap().keySet().stream()
        .filter(k -> k.getResource().equals(resource))
        .collect(Collectors.toList()));
  }
}
```

#### 5.6.2 Batch Permission Checks

```java
public List<Boolean> checkPermissionsBatch(
    String principal,
    List<String> resources,
    String permission) {
  
  // Use SpiceDB LookupResources API for efficient bulk checks
  LookupResourcesRequest request = LookupResourcesRequest.newBuilder()
      .setSubject(SubjectReference.newBuilder()
          .setObject(ObjectReference.newBuilder()
              .setObjectType("user")
              .setObjectId(principal)
              .build())
          .build())
      .setPermission(permission)
      .setResourceObjectType("bucket")
      .build();
  
  Set<String> allowed = new HashSet<>();
  client.lookupResources(request).forEachRemaining(resp -> {
    allowed.add(resp.getResourceObjectId());
  });
  
  return resources.stream()
      .map(allowed::contains)
      .collect(Collectors.toList());
}
```

#### 5.6.3 ZedToken Consistency

```java
// Use ZedTokens for consistent reads after writes
public void createBucket(String bucketName, String owner) {
  // Write relationship
  WriteRelationshipsResponse writeResp = spicedb.writeRelationship(
      "bucket", bucketName,
      "owner", "user", owner
  );
  
  // Get ZedToken from write response
  ZedToken token = writeResp.getWrittenAt();
  
  // Subsequent read uses this token for consistency
  CheckPermissionRequest checkReq = CheckPermissionRequest.newBuilder()
      .setConsistency(Consistency.newBuilder()
          .setAtLeastAsFresh(token)
          .build())
      .setSubject(/* ... */)
      .setPermission("write")
      .setResource(ObjectReference.newBuilder()
          .setObjectType("bucket")
          .setObjectId(bucketName)
          .build())
      .build();
  
  boolean canWrite = spicedb.checkPermission(checkReq);
}
```

---

## 6. AWS ACL/Policy Compatibility

### 6.1 AWS S3 Authorization Model

AWS S3 uses multiple authorization layers evaluated in order:

```
1. IAM Policies (identity-based)
2. Bucket Policies (resource-based)
3. ACLs (legacy, coarse-grained)
4. S3 Block Public Access settings

Evaluation order:
1. Explicit DENY (highest priority)
2. Explicit ALLOW
3. Implicit DENY (default)
```

### 6.2 Canned ACLs Implementation

```java
public enum CannedACL {
  PRIVATE("private"),
  PUBLIC_READ("public-read"),
  PUBLIC_READ_WRITE("public-read-write"),
  AUTHENTICATED_READ("authenticated-read"),
  BUCKET_OWNER_READ("bucket-owner-read"),
  BUCKET_OWNER_FULL_CONTROL("bucket-owner-full-control");
  
  private final String value;
  
  public void applyToSpiceDB(String resource, String owner) {
    switch (this) {
      case PRIVATE:
        // Only owner has access
        spicedb.writeRelationship("bucket", resource, "owner", "user", owner);
        break;
      
      case PUBLIC_READ:
        // Owner + public read
        spicedb.writeRelationship("bucket", resource, "owner", "user", owner);
        spicedb.writeRelationship("bucket", resource, "viewer", "user", "*");
        break;
      
      case PUBLIC_READ_WRITE:
        // Public read/write
        spicedb.writeRelationship("bucket", resource, "editor", "user", "*");
        break;
      
      case AUTHENTICATED_READ:
        // Owner + any authenticated user can read
        spicedb.writeRelationship("bucket", resource, "owner", "user", owner);
        spicedb.writeRelationship("bucket", resource, "viewer", "user", "authenticated");
        break;
      
      case BUCKET_OWNER_FULL_CONTROL:
        // Bucket owner + object owner
        spicedb.writeRelationship("object", resource, "owner", "user", owner);
        String bucketOwner = getBucketOwner(resource);
        spicedb.writeRelationship("object", resource, "admin", "user", bucketOwner);
        break;
    }
  }
}
```

### 6.3 Bucket Policy Implementation

#### 6.3.1 Policy Document Structure

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowPublicRead",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::my-bucket/*"
    },
    {
      "Sid": "DenyUnencryptedObjectUploads",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::my-bucket/*",
      "Condition": {
        "StringNotEquals": {
          "s3:x-amz-server-side-encryption": "AES256"
        }
      }
    }
  ]
}
```

#### 6.3.2 Policy Evaluation Engine

```java
public class BucketPolicyEvaluator {
  
  public AuthorizationDecision evaluate(
      BucketPolicy policy,
      String principal,
      String action,
      String resource,
      Map<String, String> context) {
    
    // Step 1: Check for explicit DENY
    for (Statement stmt : policy.getStatements()) {
      if (stmt.getEffect() == Effect.DENY &&
          matches(stmt, principal, action, resource, context)) {
        return AuthorizationDecision.DENY;
      }
    }
    
    // Step 2: Check for explicit ALLOW
    for (Statement stmt : policy.getStatements()) {
      if (stmt.getEffect() == Effect.ALLOW &&
          matches(stmt, principal, action, resource, context)) {
        return AuthorizationDecision.ALLOW;
      }
    }
    
    // Step 3: Implicit DENY
    return AuthorizationDecision.DENY;
  }
  
  private boolean matches(
      Statement stmt,
      String principal,
      String action,
      String resource,
      Map<String, String> context) {
    
    // Check principal
    if (!matchesPrincipal(stmt.getPrincipal(), principal)) {
      return false;
    }
    
    // Check action
    if (!matchesAction(stmt.getActions(), action)) {
      return false;
    }
    
    // Check resource
    if (!matchesResource(stmt.getResources(), resource)) {
      return false;
    }
    
    // Check conditions
    if (!evaluateConditions(stmt.getConditions(), context)) {
      return false;
    }
    
    return true;
  }
  
  private boolean evaluateConditions(
      List<Condition> conditions,
      Map<String, String> context) {
    
    for (Condition cond : conditions) {
      switch (cond.getOperator()) {
        case "StringEquals":
          if (!context.get(cond.getKey()).equals(cond.getValue())) {
            return false;
          }
          break;
        
        case "IpAddress":
          if (!isIpInRange(context.get("aws:SourceIp"), cond.getValue())) {
            return false;
          }
          break;
        
        case "DateGreaterThan":
          if (!isAfter(context.get("aws:CurrentTime"), cond.getValue())) {
            return false;
          }
          break;
        
        // ... other condition operators
      }
    }
    
    return true;
  }
}
```

### 6.4 Condition Keys Support

```java
public class ConditionContext {
  // Request context
  private String sourceIp;        // aws:SourceIp
  private String userAgent;       // aws:UserAgent
  private ZonedDateTime currentTime;  // aws:CurrentTime
  private String secureTransport; // aws:SecureTransport
  
  // S3-specific
  private String bucketPrefix;    // s3:prefix
  private String aclGranted;      // s3:x-amz-acl
  private String sseAlgorithm;    // s3:x-amz-server-side-encryption
  private String mfaAuthAge;      // s3:x-amz-mfa
  
  // VPC
  private String vpcId;           // aws:SourceVpc
  private String vpcEndpointId;   // aws:SourceVpce
  
  public static ConditionContext fromRequest(ContainerRequestContext httpRequest) {
    ConditionContext ctx = new ConditionContext();
    ctx.sourceIp = httpRequest.getHeaderString("X-Forwarded-For");
    ctx.userAgent = httpRequest.getHeaderString("User-Agent");
    ctx.currentTime = ZonedDateTime.now();
    ctx.secureTransport = httpRequest.getUriInfo().getRequestUri().getScheme().equals("https") ? "true" : "false";
    // ... extract S3-specific headers
    return ctx;
  }
}
```

### 6.5 Example Bucket Policies

#### 6.5.1 Public Read Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicRead",
    "Effect": "Allow",
    "Principal": "*",
    "Action": ["s3:GetObject", "s3:GetObjectVersion"],
    "Resource": "arn:aws:s3:::my-public-bucket/*"
  }]
}
```

#### 6.5.2 IP Restriction Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "AllowFromCorporateNetwork",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:*",
    "Resource": "arn:aws:s3:::corporate-data/*",
    "Condition": {
      "IpAddress": {
        "aws:SourceIp": ["192.168.0.0/16", "10.0.0.0/8"]
      }
    }
  }]
}
```

#### 6.5.3 MFA-Required Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "DenyDeleteWithoutMFA",
    "Effect": "Deny",
    "Principal": "*",
    "Action": "s3:DeleteObject",
    "Resource": "arn:aws:s3:::critical-data/*",
    "Condition": {
      "BoolIfExists": {
        "aws:MultiFactorAuthPresent": "false"
      }
    }
  }]
}
```

---

## 7. Alternative Solutions

### 7.1 Open Policy Agent (OPA/Rego)

**Overview**: Policy-as-code engine for flexible authorization

**Pros**:
- Declarative policy language (Rego)
- Decoupled from application code
- Version control for policies
- Testing framework for policies

**Cons**:
- Learning curve for Rego
- Performance overhead (policy evaluation)
- No native relationship modeling

**Use Case**: Complex conditional logic, compliance policies

**Example Rego Policy**:
```rego
package ozone.authorization

import future.keywords.if

default allow = false

# Allow if user is tenant admin
allow if {
  input.principal.roles[_] == concat("-", [input.resource.tenant, "AdminRole"])
}

# Allow if user owns the bucket
allow if {
  input.resource.type == "bucket"
  input.resource.owner == input.principal.id
}

# Deny if accessing from blacklisted IP
allow = false if {
  input.context.sourceIp in data.blacklisted_ips
}
```

### 7.2 AWS Cedar

**Overview**: AWS's new policy language with formal verification

**Pros**:
- Formal verification (correctness guarantees)
- AWS-compatible syntax
- Strong typing
- Fast evaluation

**Cons**:
- New technology (less mature)
- Limited tooling
- AWS-specific

**Use Case**: AWS migration, need for policy correctness guarantees

**Example Cedar Policy**:
```cedar
permit (
  principal in TenantGroup::"acme-corp-users",
  action in [S3::GetObject, S3::ListBucket],
  resource in Bucket::"acme-data"
) when {
  context.sourceIp in CIDR::"192.168.0.0/16"
};

forbid (
  principal,
  action == S3::DeleteObject,
  resource in Bucket::"critical-data"
) unless {
  principal.mfaAuthenticated == true
};
```

### 7.3 Vault/KMS Integration

**For Secret Management**:

```java
public class VaultSecretManager {
  
  private final VaultTemplate vaultTemplate;
  
  public AccessKeyPair generateAccessKey(String tenantId, String userId) {
    // Store secret in Vault with metadata
    Map<String, String> data = new HashMap<>();
    data.put("accessKeyId", generateAccessKeyId());
    data.put("secretAccessKey", generateSecretKey());
    data.put("tenantId", tenantId);
    data.put("userId", userId);
    data.put("createdAt", Instant.now().toString());
    
    vaultTemplate.write(
        String.format("secret/ozone/accesskeys/%s", tenantId),
        data
    );
    
    return new AccessKeyPair(data.get("accessKeyId"), data.get("secretAccessKey"));
  }
  
  public void rotateAccessKey(String accessKeyId) {
    // Implement key rotation logic
  }
}
```

**For Encryption Key Management**:
```java
public class KMSEncryptionService {
  
  private final AwsKms kmsClient;
  
  public byte[] encryptData(String tenantId, byte[] plaintext) {
    // Use tenant-specific KMS key
    String keyId = String.format("alias/ozone-tenant-%s", tenantId);
    
    EncryptRequest request = EncryptRequest.builder()
        .keyId(keyId)
        .plaintext(SdkBytes.fromByteArray(plaintext))
        .build();
    
    EncryptResponse response = kmsClient.encrypt(request);
    return response.ciphertextBlob().asByteArray();
  }
}
```

### 7.4 Identity Provider Comparison

| Feature | AWS Cognito | Okta | Keycloak |
|---------|-------------|------|----------|
| **Deployment** | SaaS only | SaaS / On-prem | Self-hosted / SaaS |
| **OAuth2/OIDC** | ✓ | ✓ | ✓ |
| **SAML 2.0** | ✓ | ✓ | ✓ |
| **MFA** | SMS, TOTP, WebAuthn | SMS, TOTP, Push, WebAuthn | TOTP, WebAuthn |
| **Social Login** | Google, Facebook, Amazon | Many providers | Google, GitHub, etc. |
| **User Management** | Built-in | Built-in | Built-in |
| **LDAP/AD Integration** | Limited | ✓ | ✓ |
| **Custom Attributes** | Limited | ✓ | ✓ |
| **Pricing** | Pay-per-MAU ($0.0055/MAU) | $2-15/user/month | Free (self-hosted) |
| **Scalability** | Auto-scaling | Enterprise-grade | Horizontal (clustered) |
| **Customization** | Limited | Extensions | Full control |
| **Vendor Lock-in** | High | Medium | Low |
| **Best For** | AWS-native apps | Enterprise SSO | Self-hosted, cost-sensitive |

**Recommendation**: **Keycloak** for MVP (self-hosted, free, full-featured)

---

## 8. Security & Compliance

### 8.1 Audit Logging

#### 8.1.1 Audit Event Structure

```java
public class AuthorizationAuditEvent {
  private String eventId;            // UUID
  private Instant timestamp;
  private String principal;          // user ID
  private String sourceIp;
  private String userAgent;
  private String tenantId;
  private String resource;           // bucket/object path
  private String action;             // s3:GetObject, etc.
  private String decision;           // ALLOW / DENY
  private String authzSystem;        // Ranger / SpiceDB
  private long latencyMs;
  private Map<String, String> context;
  
  public void writeToAuditLog() {
    // Write to immutable audit trail
    // Options:
    //  - HDFS append-only
    //  - S3 with object lock
    //  - Splunk / ELK
    //  - CloudWatch Logs
  }
}
```

#### 8.1.2 Audit Requirements

- [ ] **Immutability**: Logs cannot be modified/deleted
- [ ] **Retention**: 90+ days (configurable)
- [ ] **Encryption**: At rest and in transit
- [ ] **Completeness**: All authorization decisions logged
- [ ] **Searchability**: Indexed by user, resource, time
- [ ] **SIEM Integration**: Export to Splunk/ELK
- [ ] **Alerting**: Real-time alerts on suspicious activity

#### 8.1.3 Sample Audit Log

```json
{
  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": "2025-10-14T10:30:45.123Z",
  "principal": "bob@acme.com",
  "sourceIp": "203.0.113.42",
  "userAgent": "aws-cli/2.13.5",
  "tenantId": "acme-corp",
  "resource": "s3://acme-vol/ml-datasets/train.csv",
  "action": "s3:GetObject",
  "decision": "ALLOW",
  "authzSystem": "SpiceDB",
  "latencyMs": 8,
  "context": {
    "bucketOwner": "alice@acme.com",
    "objectSize": "1048576000",
    "encryption": "AES256"
  }
}
```

### 8.2 PII Handling

#### 8.2.1 Data Classification

| Data Type | Sensitivity | Encryption | Retention | Access Control |
|-----------|-------------|------------|-----------|----------------|
| User email | PII | ✓ At rest | Account lifetime | Admin only |
| Access keys | Sensitive | ✓ At rest + transit | Until revoked | User + Admin |
| Bucket names | Public metadata | Not required | Permanent | Tenant users |
| Object keys | Metadata | Optional | Per policy | Bucket ACL |
| Audit logs | Sensitive | ✓ | 90 days | Security team |

#### 8.2.2 GDPR Compliance

**Right to Deletion**:
```java
public void deleteUserData(String userId) throws IOException {
  // 1. Revoke all access keys
  List<String> accessIds = omMetadataManager.getPrincipalToAccessIdsTable()
      .get(userId)
      .getAccessIds();
  for (String accessId : accessIds) {
    revokeAccessKey(accessId);
  }
  
  // 2. Remove from all tenant roles
  for (String tenantId : getUserTenants(userId)) {
    removeUserFromTenant(userId, tenantId);
  }
  
  // 3. Delete user metadata
  omMetadataManager.getPrincipalToAccessIdsTable().delete(userId);
  
  // 4. Anonymize audit logs
  auditLog.anonymize(userId, "DELETED_USER_" + UUID.randomUUID());
}
```

**Data Breach Notification**:
```java
public void handleSecurityIncident(SecurityIncident incident) {
  // Log incident
  securityLog.write(incident);
  
  // Alert security team
  alerting.sendPagerDuty(
      "CRITICAL: Potential data breach - " + incident.getDescription()
  );
  
  // If PII exposed, trigger breach notification workflow
  if (incident.involvesP11()) {
    breachNotificationService.initiate(incident);
  }
}
```

### 8.3 Key Rotation

#### 8.3.1 Access Key Rotation Policy

```java
@Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
public void enforceKeyRotationPolicy() {
  Instant rotationThreshold = Instant.now().minus(90, ChronoUnit.DAYS);
  
  // Find keys older than 90 days
  List<AccessKeyInfo> oldKeys = accessKeyTable.stream()
      .filter(key -> key.getCreatedAt().isBefore(rotationThreshold))
      .filter(key -> !key.isMarkedForDeletion())
      .collect(Collectors.toList());
  
  for (AccessKeyInfo key : oldKeys) {
    // Notify user
    notificationService.sendEmail(
        key.getUserEmail(),
        "Access Key Rotation Required",
        String.format("Your access key %s is %d days old. Please rotate it.",
            key.getAccessKeyId(),
            ChronoUnit.DAYS.between(key.getCreatedAt(), Instant.now()))
    );
    
    // Mark for deletion in 30 days if not rotated
    if (key.getCreatedAt().isBefore(Instant.now().minus(120, ChronoUnit.DAYS))) {
      key.setMarkedForDeletion(true);
      key.setDeletionDate(Instant.now().plus(30, ChronoUnit.DAYS));
    }
  }
}
```

### 8.4 Least Privilege

#### 8.4.1 Permission Boundaries

```java
public class PermissionBoundary {
  
  public void enforceMaxPermissions(String userId, Set<String> requestedPermissions) {
    // Define maximum allowed permissions per user
    Set<String> maxAllowed = getUserMaxPermissions(userId);
    
    // Ensure requested permissions don't exceed boundary
    Set<String> exceeding = requestedPermissions.stream()
        .filter(perm -> !maxAllowed.contains(perm))
        .collect(Collectors.toSet());
    
    if (!exceeding.isEmpty()) {
      throw new PermissionDeniedException(
          "Requested permissions exceed boundary: " + exceeding
      );
    }
  }
}
```

#### 8.4.2 Just-in-Time Access

```java
public class JITAccessManager {
  
  public TemporaryCredentials grantTemporaryAccess(
      String userId,
      String resource,
      String permission,
      Duration duration) {
    
    // Generate temporary credentials
    String tempAccessKeyId = "TEMP-" + UUID.randomUUID();
    String tempSecret = generateSecureRandom(32);
    
    // Grant time-bound permission in SpiceDB
    spicedb.writeRelationship(
        "bucket", resource,
        "viewer", "user", userId,
        Caveat.expiry(Instant.now().plus(duration))
    );
    
    // Schedule automatic revocation
    scheduler.schedule(
        () -> revokeTemporaryAccess(tempAccessKeyId),
        duration.toMillis(),
        TimeUnit.MILLISECONDS
    );
    
    return new TemporaryCredentials(tempAccessKeyId, tempSecret, duration);
  }
}
```

### 8.5 Tenant Isolation

#### 8.5.1 Network Isolation

```yaml
# Kubernetes NetworkPolicy for tenant isolation
apiVersion: networking.k8.io/v1
kind: NetworkPolicy
metadata:
  name: tenant-acme-corp-isolation
spec:
  podSelector:
    matchLabels:
      tenant: acme-corp
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          tenant: acme-corp
  egress:
  - to:
    - podSelector:
        matchLabels:
          component: ozone-datanode
```

#### 8.5.2 Data Isolation

```java
// Tenant-specific encryption keys
public class TenantEncryptionService {
  
  private final Map<String, SecretKey> tenantKeys = new ConcurrentHashMap<>();
  
  public byte[] encryptForTenant(String tenantId, byte[] plaintext) {
    SecretKey key = tenantKeys.computeIfAbsent(tenantId, tid -> {
      // Fetch tenant-specific key from KMS
      return kms.getKey("tenant/" + tid);
    });
    
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key);
    return cipher.doFinal(plaintext);
  }
}
```

#### 8.5.3 Resource Quotas

```java
public class TenantQuotaEnforcer {
  
  public void enforcequotas(String tenantId, ResourceRequest request) {
    TenantQuota quota = quotaService.getQuota(tenantId);
    TenantUsage usage = usageService.getCurrentUsage(tenantId);
    
    // Check storage quota
    if (usage.getStorageBytes() + request.getSize() > quota.getMaxStorageBytes()) {
      throw new QuotaExceededException("Storage quota exceeded");
    }
    
    // Check request rate
    if (usage.getRequestsPerSecond() > quota.getMaxRequestsPerSecond()) {
      throw new RateLimitExceededException("Request rate limit exceeded");
    }
  }
}
```

---

## 9. Testing & Rollout

### 9.1 Unit Tests

```java
@Test
void testTenantCreationWithRangerPolicies() {
  // Arrange
  String tenantId = "test-tenant";
  TenantCreateRequest request = new TenantCreateRequest();
  request.setTenantId(tenantId);
  request.setAdminUsers(List.of("admin@test.com"));
  
  // Act
  Response response = tenantEndpoint.createTenant(request);
  
  // Assert
  assertEquals(201, response.getStatus());
  
  // Verify Ranger roles created
  verify(rangerClient).createRole(argThat(role ->
      role.getName().equals(tenantId + "-UserRole")));
  verify(rangerClient).createRole(argThat(role ->
      role.getName().equals(tenantId + "-AdminRole")));
  
  // Verify policies created
  verify(rangerClient, times(2)).createPolicy(any(Policy.class));
}

@Test
void testUnauthorizedAccessDenied() {
  // Arrange
  AuthorizationRequest req = new AuthorizationRequest(
      "bob@test.com",
      "bucket:private-data",
      "s3:GetObject"
  );
  
  // Act
  boolean allowed = authzService.checkPermission(req);
  
  // Assert
  assertFalse(allowed, "Unauthorized access should be denied");
}
```

### 9.2 Integration Tests

```java
@SpringBootTest
@TestPropertySource(properties = {
    "ozone.om.multitenancy.enabled=true",
    "ozone.om.ranger.service.name=test-ranger"
})
class MultiTenancyIntegrationTest {
  
  @Autowired
  private ObjectStore objectStore;
  
  @Autowired
  private S3Gateway s3Gateway;
  
  @Test
  void testEndToEndS3OperationWithTenantContext() throws Exception {
    // 1. Create tenant
    objectStore.createTenant("integration-test-tenant");
    
    // 2. Create user and access key
    String userId = "testuser@example.com";
    String accessKeyId = objectStore.assignUserToTenant(
        userId, "integration-test-tenant", false);
    
    // 3. Create bucket via S3 API
    S3Client s3 = S3Client.builder()
        .credentialsProvider(() -> AwsBasicCredentials.create(
            accessKeyId, getSecretKey(accessKeyId)))
        .build();
    
    s3.createBucket(CreateBucketRequest.builder()
        .bucket("test-bucket")
        .build());
    
    // 4. Upload object
    s3.putObject(PutObjectRequest.builder()
        .bucket("test-bucket")
        .key("test-object.txt")
        .build(),
        RequestBody.fromString("Hello, World!"));
    
    // 5. Verify object readable
    GetObjectResponse getResp = s3.getObject(GetObjectRequest.builder()
        .bucket("test-bucket")
        .key("test-object.txt")
        .build());
    
    assertEquals("Hello, World!", getResp.responseInputStream().readAllBytes());
    
    // 6. Verify tenant isolation - different user can't access
    String otherUserId = "otheruser@example.com";
    S3Client otherS3 = createS3ClientForUser(otherUserId, "other-tenant");
    
    assertThrows(S3Exception.class, () -> {
      otherS3.getObject(GetObjectRequest.builder()
          .bucket("test-bucket")
          .key("test-object.txt")
          .build());
    });
  }
}
```

### 9.3 Load Tests

```java
@LoadTest
class AuthorizationLoadTest {
  
  @Test
  @LoadProfile(users = 1000, duration = "5m", rampUp = "1m")
  void testAuthorizationThroughput() {
    // Simulate 1000 concurrent users making authorization checks
    // Target: 10,000 checks/sec with p99 < 50ms
    
    gatling.scenario("Authorization Checks")
        .exec(
            http("Check Permission")
                .post("/api/v1/authz/check")
                .body(StringBody("""
                    {
                      "principal": "user-${userId}",
                      "resource": "bucket:data-${bucketId}",
                      "permission": "read"
                    }
                    """))
                .check(status().is(200))
                .check(jsonPath("$.allowed").is("true"))
        )
        .inject(rampUsers(1000).during(60))
        .protocols(httpProtocol);
  }
}
```

### 9.4 Staging Rollout Plan

| Week | Activity | Success Criteria |
|------|----------|------------------|
| 1-2 | Internal testing | All unit/integration tests pass; manual QA complete |
| 3 | Deploy to staging | Staging environment stable for 7 days |
| 4 | Beta users (2 tenants) | No critical bugs; positive feedback |
| 5 | Limited GA (10% traffic) | Error rate < 0.1%; latency p99 < 100ms |
| 6 | Increase to 50% | Metrics stable; no rollbacks |
| 7+ | Full rollout (100%) | All tenants migrated; old system decommissioned |

### 9.5 Feature Flags

```yaml
# feature-flags.yaml
flags:
  oauth2_enabled:
    default: false
    description: "Enable OAuth2 authentication for Admin Gateway"
    
  ranger_authorization:
    default: true
    description: "Use Ranger for authorization (MVP)"
    
  spicedb_dual_write:
    default: false
    description: "Write to both Ranger and SpiceDB"
    
  spicedb_dual_read:
    default: false
    description: "Read from both systems and compare"
    
  spicedb_reads_enabled:
    default: false
    description: "Use SpiceDB for authorization reads"
    
  spicedb_writes_only:
    default: false
    description: "Write only to SpiceDB (cutover complete)"
    
  aws_acl_support:
    default: false
    description: "Enable AWS-style canned ACLs"
    
  bucket_policy_support:
    default: false
    description: "Enable bucket policy evaluation"
```

### 9.6 Monitoring & Alerting

#### 9.6.1 Key Metrics

```yaml
metrics:
  - name: authorization_latency_ms
    type: histogram
    labels: [authz_system, decision]
    percentiles: [50, 95, 99]
    
  - name: authorization_error_rate
    type: counter
    labels: [authz_system, error_type]
    
  - name: tenant_count
    type: gauge
    
  - name: active_access_keys
    type: gauge
    labels: [tenant_id]
    
  - name: oauth2_token_validation_failures
    type: counter
    labels: [reason]
    
  - name: dual_write_lag_ms
    type: histogram
    description: "Lag between Ranger and SpiceDB writes"
    
  - name: cache_hit_rate
    type: gauge
    labels: [cache_type]
```

#### 9.6.2 Alerts

```yaml
alerts:
  - name: HighAuthorizationErrorRate
    expr: rate(authorization_error_rate[5m]) > 0.01
    severity: critical
    message: "Authorization error rate > 1%"
    
  - name: HighAuthorizationLatency
    expr: histogram_quantile(0.99, authorization_latency_ms) > 0.1
    severity: warning
    message: "Authorization p99 latency > 100ms"
    
  - name: SpiceDBDown
    expr: up{job="spicedb"} == 0
    severity: critical
    message: "SpiceDB is down"
    
  - name: DualWriteLag
    expr: histogram_quantile(0.95, dual_write_lag_ms) > 1000
    severity: warning
    message: "SpiceDB dual-write lag > 1s"
```

---

## 10. Implementation Timeline

### Phase 1: Ranger MVP (Weeks 1-6)

| Week | Tasks | Deliverables |
|------|-------|--------------|
| 1-2 | **OAuth2 + Admin Gateway** | - OAuth2Filter<br>- JWT validation<br>- TenantEndpoint<br>- PolicyEndpoint<br>- Keycloak integration |
| 3-4 | **Ranger Integration** | - RangerAuthorizationFilter<br>- S3ActionMapper<br>- Policy templates<br>- OMMultiTenantManager enhancements |
| 5 | **Testing** | - Unit tests<br>- Integration tests<br>- Manual QA<br>- Documentation |
| 6 | **Beta Deployment** | - Deploy to staging<br>- Onboard 2 beta tenants<br>- Collect feedback |

**Milestone**: Working MVP with OAuth2 auth + Ranger authz

### Phase 2: SpiceDB Migration (Weeks 7-12)

| Week | Tasks | Deliverables |
|------|-------|--------------|
| 7-8 | **SpiceDB Setup** | - Schema design<br>- SpiceDBAccessController<br>- Dual-write implementation<br>- Migration script |
| 9-10 | **Dual-Read & Validation** | - DualReadAccessController<br>- Comparison logic<br>- Monitoring dashboard<br>- Fix discrepancies |
| 11 | **Cutover** | - Gradual rollout (1% → 100%)<br>- Monitoring<br>- Rollback testing |
| 12 | **Cleanup** | - Remove Ranger dependencies<br>- Archive data<br>- Update docs |

**Milestone**: SpiceDB as primary authorization system

### Phase 3: AWS Compatibility (Weeks 13-16)

| Week | Tasks | Deliverables |
|------|-------|--------------|
| 13-14 | **ACL Implementation** | - CannedACL support<br>- ACL evaluation engine<br>- GetBucketAcl/PutBucketAcl APIs |
| 15 | **Bucket Policy** | - Policy parser<br>- Condition evaluator<br>- PutBucketPolicy API |
| 16 | **Testing & Documentation** | - Compatibility tests<br>- AWS parity matrix<br>- Migration guide |

**Milestone**: AWS S3 ACL/Policy compatibility

### Total Timeline: 16 Weeks

```
Timeline Gantt Chart:

Week:  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16
       |--|--|--|--|--|--|--|--|--|--|--|--|--|--|--|
OAuth2 [==]
Admin  [======]
Ranger    [======]
Tests           [==]
Beta              [=]
SpiceDB              [=====]
Dual-R                 [=====]
Cutover                      [==]
Cleanup                         [=]
ACL                                 [======]
Policy                                 [==]
Docs                                      [=]
```

---

## 11. Appendices

### Appendix A: Glossary

- **Access ID**: Unique identifier (like AWS Access Key ID) for S3 authentication
- **ACL**: Access Control List, AWS S3's legacy permission model
- **Bucket Policy**: Resource-based policy attached to S3 buckets
- **Canned ACL**: Predefined ACL (e.g., public-read, private)
- **Delegated Admin**: User who can manage permissions within a tenant
- **IAM**: Identity and Access Management
- **OIDC**: OpenID Connect, authentication protocol built on OAuth2
- **OM**: Ozone Manager, metadata service in Apache Ozone
- **Principal**: User or service account identity
- **ReBAC**: Relationship-Based Access Control
- **Tenant**: Isolated namespace for multi-tenancy
- **ZedToken**: SpiceDB's consistency token for point-in-time reads

### Appendix B: References

- [Apache Ozone S3 Multi-Tenancy Docs](https://ozone.apache.org/docs/edge/feature/s3-multi-tenancy.html)
- [Apache Ranger Documentation](https://ranger.apache.org/)
- [SpiceDB Documentation](https://authzed.com/docs)
- [AWS S3 IAM Policies](https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-policy-language-overview.html)
- [OAuth 2.0 RFC 6749](https://datatracker.ietf.org/doc/html/rfc6749)
- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)
- [Google Zanzibar Paper](https://research.google/pubs/pub48190/)

### Appendix C: API Reference

#### Admin Gateway REST API

**Base URL**: `http://admin-gateway:9880/api/v1`

##### Tenants

```
POST   /tenants              Create tenant
GET    /tenants              List tenants
GET    /tenants/{id}         Get tenant details
DELETE /tenants/{id}         Delete tenant

POST   /tenants/{id}/users   Assign user to tenant
DELETE /tenants/{id}/users/{userId}  Remove user
```

##### Policies

```
POST   /policies             Create custom policy
GET    /policies             List policies
GET    /policies/{id}        Get policy details
PUT    /policies/{id}        Update policy
DELETE /policies/{id}        Delete policy
```

##### Roles

```
POST   /roles                Create role
GET    /roles                List roles
PUT    /roles/{id}/users     Assign user to role
DELETE /roles/{id}/users/{userId}  Remove user from role
```

##### Access Keys

```
POST   /access-keys          Generate new access key
GET    /access-keys          List user's access keys
DELETE /access-keys/{id}     Revoke access key
POST   /access-keys/{id}/rotate  Rotate access key
```

### Appendix D: Migration Scripts

See Section 5.4.1 for Ranger → SpiceDB migration script.

---

## Summary

This proposal provides a comprehensive roadmap for implementing a modern IAM system with multi-tenancy support for Apache Ozone's S3 Gateway. The phased approach balances schedule pressure with long-term scalability:

1. **MVP (6 weeks)**: Apache Ranger + OAuth2 - Fast time-to-market
2. **Evolution (6 weeks)**: SpiceDB migration - Modern ReBAC, better scaling
3. **Compatibility (4 weeks)**: AWS ACL/Policy - Improved user experience

**Key Advantages**:
- Proven technologies (Ranger, SpiceDB, Keycloak)
- Incremental migration with rollback capability
- Comprehensive security & compliance
- AWS S3 compatibility for user familiarity

**Next Steps**:
1. Review and approve this proposal
2. Set up development environment (Ranger, Keycloak, SpiceDB)
3. Begin OAuth2Filter implementation
4. Weekly sync meetings to track progress

---

**Document Version**: 1.0  
**Last Updated**: 2025-10-14  
**Authors**: Platform Engineering Team  
**Reviewers**: Security, Compliance, Engineering Leadership
