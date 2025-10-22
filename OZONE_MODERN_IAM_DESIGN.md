# Apache Ozone Modern IAM Design: Replacing Kerberos & Ranger

**Version:** 1.0
**Date:** 2025-01-19
**Status:** Design Proposal
**Author:** Platform Engineering Team

---

## Executive Summary

This document provides a detailed design for modernizing Apache Ozone's authentication and authorization stack by replacing Kerberos and Ranger with modern alternatives while maintaining HDFS protocol compatibility where necessary.

### Current State Analysis

**Authentication:**
- ✅ **S3 Gateway**: AWS SigV4 (good, keep this)
- ❌ **HDFS Protocol**: Kerberos (tightly coupled, hard to remove)
- ❌ **Admin APIs**: Basic auth or Kerberos (needs OAuth2/OIDC)
- ✅ **Multi-tenant**: S3 access keys via `tenantId$userId` format (good pattern)

**Authorization:**
- ✅ **Native ACLs**: `OzoneNativeAuthorizer` (works for basic cases)
- ✅ **Ranger Integration**: `RangerClientMultiTenantAccessController` (production-ready)
- ❌ **Scalability**: Ranger struggles at 1M+ tenants
- ❌ **Flexibility**: Limited ABAC, no ReBAC

### Key Design Decision

**YOU CANNOT FULLY REMOVE KERBEROS** - Here's why:

```
┌────────────────────────────────────────────────────────────┐
│  Protocol Layer Analysis                                   │
├────────────────────────────────────────────────────────────┤
│  S3 Protocol (Port 9878)                                   │
│  - Already uses SigV4 authentication                       │
│  - ✅ NO Kerberos dependency                               │
│  - ✅ Can use OAuth2 for admin operations                  │
│  - Status: READY for modernization                         │
├────────────────────────────────────────────────────────────┤
│  HDFS Protocol (o3fs://, ofs://)                           │
│  - Uses Hadoop RPC protocol                                │
│  - ❌ TIGHTLY COUPLED to Kerberos in core Hadoop          │
│  - Used by: Spark, Hive, Presto, Flink                    │
│  - Removing Kerberos = breaking compatibility              │
│  - Status: KEEP Kerberos for backward compatibility        │
├────────────────────────────────────────────────────────────┤
│  Internal RPC (OM ↔ SCM ↔ Datanode)                       │
│  - Currently uses Kerberos                                 │
│  - ⚠️ Can potentially use mTLS certificates               │
│  - Status: OPTIONAL migration (low priority)               │
└────────────────────────────────────────────────────────────┘
```

### Recommended Approach: Hybrid Authentication

**Keep Kerberos for HDFS protocol, add OAuth2/OIDC for everything else:**

```
┌─────────────────────────────────────────────────────────────┐
│  Client Type          │  Protocol    │  Authentication       │
├─────────────────────────────────────────────────────────────┤
│  AWS SDK / S3 CLI     │  S3 REST     │  SigV4 (existing)     │
│  Web Portal / Admin   │  REST API    │  OAuth2/OIDC (NEW)    │
│  Spark / Hive / Presto│  HDFS (RPC)  │  Kerberos (KEEP)      │
│  Internal daemons     │  RPC         │  Kerberos or mTLS     │
└─────────────────────────────────────────────────────────────┘
```

### Authorization Modernization Options

| Option | Complexity | Performance | Use Case |
|--------|-----------|-------------|----------|
| **Keep Native ACLs** | Low | Excellent (in-memory) | < 100 tenants, simple ACLs |
| **Keep Ranger** | Medium | Good (10-50ms) | 100-10K tenants, enterprise features |
| **Migrate to SpiceDB** | High | Excellent (<5ms) | 10K+ tenants, Google-scale, ReBAC needed |
| **Use OPA (Rego)** | Medium | Medium (20-100ms) | Complex policy logic, compliance requirements |
| **Hybrid** | High | Best of both | Large-scale with varied needs |

---

## Part 1: What Can Be Replaced?

### 1.1 Kerberos Replacement Analysis

#### ✅ Can Replace (S3 Gateway & Admin APIs)

**Scope:** S3 REST API, Admin Gateway REST API, monitoring APIs

**Current State:**
```java
// S3 Gateway currently supports SigV4 (no Kerberos dependency)
// hadoop-ozone/s3gateway/src/main/java/org/apache/hadoop/ozone/s3/signature/SignatureProcessor.java
public class SignatureProcessor {
  public AuthenticationResult authenticate(HttpServletRequest request) {
    // AWS SigV4 signature validation
    String authorization = request.getHeader("Authorization");
    // ... validates against S3 secret stored in OM
  }
}
```

**Replacement Strategy:**
- ✅ **Keep SigV4** for S3 API (already modern, AWS-compatible)
- ✅ **Add OAuth2/OIDC** for Admin Gateway APIs
- ✅ **Add JWT validation** for service-to-service calls

**New Architecture:**
```
┌─────────────────────────────────────────────────────────────┐
│  S3 Gateway Authentication Flow (KEEP EXISTING)             │
│  1. Client signs request with SigV4                         │
│  2. S3Gateway extracts accessKeyId (tenantId$userId)        │
│  3. OM retrieves S3 secret for validation                   │
│  4. Signature matches → Authenticated                       │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Admin Gateway Authentication Flow (NEW)                    │
│  1. User logs in via OAuth2 (Keycloak/Okta)                │
│  2. IdP issues JWT with claims (tenant, roles, groups)      │
│  3. Admin Gateway validates JWT signature                   │
│  4. Extract tenant context from JWT claims                  │
│  5. Forward to OM with authenticated user context           │
└─────────────────────────────────────────────────────────────┘
```

#### ❌ Cannot Replace (HDFS Protocol)

**Scope:** o3fs://, ofs:// filesystem access from Spark/Hive/Presto/Flink

**Why Kerberos is Required:**
1. **Hadoop RPC Protocol Dependency**: Core Hadoop RPC (`org.apache.hadoop.ipc.RPC`) is hardcoded to use SASL/Kerberos
2. **Client Compatibility**: Spark, Hive, Presto expect Kerberos for HDFS-compatible filesystems
3. **Security Context**: `UserGroupInformation` (UGI) throughout Hadoop ecosystem assumes Kerberos tickets

**Attempted Workarounds (NOT RECOMMENDED):**
```java
// Option 1: Fork Hadoop RPC (MASSIVE EFFORT, breaks compatibility)
// - Reimplement org.apache.hadoop.ipc.Server
// - Replace SASL with OAuth2 bearer tokens
// - Update all clients (Spark, Hive, etc.)
// Verdict: NOT FEASIBLE

// Option 2: Kerberos → OAuth2 Gateway (COMPLEX, added latency)
// - Run a proxy that converts OAuth2 tokens to Kerberos tickets
// - MIT Kerberos can issue tickets from external auth
// Verdict: TOO COMPLEX for marginal benefit

// Option 3: Use o3fs with S3A connector (LIMITED SCOPE)
// - Configure Spark/Hive to use s3a:// instead of o3fs://
// - s3a uses SigV4, no Kerberos needed
// Verdict: WORKS but requires client reconfiguration
```

**Recommended Approach:** **KEEP Kerberos for HDFS protocol**

```xml
<!-- ozone-site.xml - Hybrid configuration -->
<property>
  <name>ozone.security.enabled</name>
  <value>true</value>
  <description>Enable security (Kerberos for HDFS protocol)</description>
</property>

<property>
  <name>ozone.s3gateway.oauth2.enabled</name>
  <value>true</value>
  <description>Enable OAuth2 for S3 Gateway admin APIs (NEW)</description>
</property>

<property>
  <name>ozone.acl.authorizer.class</name>
  <value>org.apache.hadoop.ozone.security.acl.OzoneNativeAuthorizer</value>
  <description>Use native ACLs OR Ranger (not Kerberos-specific)</description>
</property>
```

### 1.2 Ranger Replacement Analysis

#### Why Consider Replacing Ranger?

**Ranger's Strengths:**
- ✅ Mature, battle-tested in Hadoop ecosystem
- ✅ Rich UI for policy management
- ✅ Excellent audit capabilities (Solr/HDFS integration)
- ✅ Multi-service support (HDFS, Hive, HBase, Kafka, Ozone)

**Ranger's Limitations:**
- ❌ **Scalability**: Policy evaluation slows down at 10K+ policies (1M tenants = 1M+ policies)
- ❌ **Latency**: 10-50ms per authorization check (too slow for high-throughput S3 workloads)
- ❌ **No ReBAC**: Cannot express relationships like "users of tenant A can access tenant B's shared bucket"
- ❌ **Limited ABAC**: Condition support is basic compared to AWS IAM
- ❌ **Operational Overhead**: Requires separate Ranger Admin, Ranger DB, Solr cluster

#### When to Replace Ranger?

```
Decision Matrix:
┌─────────────────────────────────────────────────────────────┐
│  Current State          │  Recommendation                    │
├─────────────────────────────────────────────────────────────┤
│  < 100 tenants          │  KEEP Native ACLs (no Ranger)      │
│  100-1000 tenants       │  KEEP Ranger (works well)          │
│  1K-10K tenants         │  KEEP Ranger, monitor performance  │
│  10K-100K tenants       │  MIGRATE to SpiceDB                │
│  100K-1M+ tenants       │  MIGRATE to SpiceDB (URGENT)       │
└─────────────────────────────────────────────────────────────┘

Performance Triggers for Migration:
- Authorization latency p99 > 50ms
- Ranger policy count > 10K
- Policy sync lag > 5 minutes
- Ranger Admin server CPU > 80%
```

---

## Part 2: Modernization Architecture

### 2.1 Target Architecture: Hybrid Authentication

```
┌──────────────────────────────────────────────────────────────────┐
│                  CLIENT LAYER                                     │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐ │
│  │ AWS SDK    │  │ Web Portal │  │ Spark/Hive │  │ Admin CLI  │ │
│  │ (s3a://)   │  │ (Browser)  │  │ (o3fs://)  │  │ (curl)     │ │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘ │
└────────┼───────────────┼───────────────┼───────────────┼─────────┘
         │ SigV4         │ OAuth2        │ Kerberos      │ JWT
         │               │               │               │
         ▼               ▼               ▼               ▼
┌──────────────────────────────────────────────────────────────────┐
│                  GATEWAY LAYER                                    │
│  ┌────────────────────────┐    ┌───────────────────────────────┐ │
│  │  S3 Gateway (:9878)    │    │  HDFS Gateway (o3fs/ofs)      │ │
│  │  ┌──────────────────┐  │    │  ┌─────────────────────────┐ │ │
│  │  │ SigV4 Auth       │  │    │  │ Kerberos Auth (KEEP)    │ │ │
│  │  │ (existing)       │  │    │  │ SASL/GSSAPI             │ │ │
│  │  └──────────────────┘  │    │  └─────────────────────────┘ │ │
│  │  ┌──────────────────┐  │    └───────────────────────────────┘ │
│  │  │ OAuth2 Filter    │  │    ┌───────────────────────────────┐ │
│  │  │ (NEW for admin)  │  │    │  Admin Gateway (:9880)        │ │
│  │  └──────────────────┘  │    │  ┌─────────────────────────┐ │ │
│  └────────────────────────┘    │  │ OAuth2/OIDC Filter      │ │ │
│                                 │  │ JWT Validation          │ │ │
│                                 │  └─────────────────────────┘ │ │
│                                 └───────────────────────────────┘ │
└────────────────────┬─────────────────────────────────────────────┘
                     │ All forward to OM with authenticated context
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│                  OZONE MANAGER                                    │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Authorization Layer (PLUGGABLE)                           │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │  │
│  │  │ Native ACLs  │  │ Ranger       │  │ SpiceDB (NEW)   │  │  │
│  │  │ (simple)     │  │ (current)    │  │ (scale)         │  │  │
│  │  └──────────────┘  └──────────────┘  └─────────────────┘  │  │
│  │  Selected via: ozone.acl.authorizer.class                 │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 OAuth2/OIDC Integration for S3 Gateway Admin APIs

**Use Case:** BSS (Business Support System) or management portal needs to call Admin Gateway APIs to:
- Create tenants
- Generate S3 access keys
- Manage user assignments
- Query usage metrics

**Implementation:**

```java
// hadoop-ozone/admin-gateway/src/main/java/org/apache/ozone/admin/auth/OAuth2Filter.java
package org.apache.ozone.admin.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import javax.annotation.Priority;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import java.util.Date;

/**
 * OAuth2/OIDC authentication filter for Admin Gateway.
 * Validates JWT tokens from external IdP (Keycloak/Okta/Auth0).
 */
@Provider
@PreMatching
@Priority(10)
public class OAuth2Filter implements ContainerRequestFilter {

  private static final String AUTH_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final JWTValidator jwtValidator;

  @Override
  public void filter(ContainerRequestContext ctx) {
    String authHeader = ctx.getHeaderString(AUTH_HEADER);

    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      abort(ctx, 401, "Missing Authorization header");
      return;
    }

    String token = authHeader.substring(BEARER_PREFIX.length());

    try {
      SignedJWT jwt = SignedJWT.parse(token);

      // Validate signature against IdP's public key (JWKS endpoint)
      if (!jwtValidator.validateSignature(jwt)) {
        abort(ctx, 401, "Invalid token signature");
        return;
      }

      JWTClaimsSet claims = jwt.getJWTClaimsSet();

      // Check expiration
      Date exp = claims.getExpirationTime();
      if (exp == null || exp.before(new Date())) {
        abort(ctx, 401, "Token expired");
        return;
      }

      // Extract user info
      String userId = claims.getSubject();
      String tenantId = claims.getStringClaim("tenant_id");
      String[] roles = claims.getStringArrayClaim("roles");

      // Store in request context
      ctx.setProperty("user_id", userId);
      ctx.setProperty("tenant_id", tenantId);
      ctx.setProperty("roles", roles);

    } catch (Exception e) {
      abort(ctx, 401, "Invalid token: " + e.getMessage());
    }
  }

  private void abort(ContainerRequestContext ctx, int status, String message) {
    ctx.abortWith(Response.status(status).entity(message).build());
  }
}
```

**Configuration:**

```xml
<!-- ozone-site.xml -->
<property>
  <name>ozone.admin.gateway.oauth2.enabled</name>
  <value>true</value>
</property>

<property>
  <name>ozone.admin.gateway.oauth2.issuer</name>
  <value>https://keycloak.example.com/realms/ozone</value>
  <description>OIDC issuer URL for token validation</description>
</property>

<property>
  <name>ozone.admin.gateway.oauth2.jwks.url</name>
  <value>https://keycloak.example.com/realms/ozone/protocol/openid-connect/certs</value>
  <description>JWKS endpoint for public key retrieval</description>
</property>

<property>
  <name>ozone.admin.gateway.oauth2.audience</name>
  <value>ozone-admin-api</value>
  <description>Expected audience in JWT tokens</description>
</property>
```

### 2.3 Replacing Ranger with SpiceDB

**When to Migrate:** When you have >10K tenants or need advanced access control (ReBAC, ABAC).

#### SpiceDB Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                  SpiceDB Components                           │
├──────────────────────────────────────────────────────────────┤
│  1. Schema Service                                            │
│     - Defines relationships: tenant→volume, bucket→tenant     │
│     - Zanzibar-inspired permission model                      │
│     - Versioned schema changes                                │
├──────────────────────────────────────────────────────────────┤
│  2. Permissions Service (gRPC API)                            │
│     - CheckPermission(user, resource, permission)             │
│     - LookupResources(user, permission, resourceType)         │
│     - ExpandPermissionTree(resource, permission)              │
├──────────────────────────────────────────────────────────────┤
│  3. Relationship Storage (Postgres/CockroachDB)               │
│     - Stores tuples: tenant:acme#member@user:alice            │
│     - Optimized for graph traversal                           │
│     - Supports millions of relationships                      │
├──────────────────────────────────────────────────────────────┤
│  4. Consistency Layer (ZedTokens)                             │
│     - Read-your-writes consistency                            │
│     - Point-in-time snapshots                                 │
│     - Distributed caching support                             │
└──────────────────────────────────────────────────────────────┘
```

#### SpiceDB Schema for Ozone

```zed
// ozone-schema.zed

definition user {}

definition tenant {
  relation admin: user
  relation member: user

  permission manage = admin
  permission access = member + admin
}

definition volume {
  relation tenant: tenant
  relation owner: user

  permission read = tenant->access + owner
  permission write = tenant->manage + owner
}

definition bucket {
  relation volume: volume
  relation owner: user
  relation viewer: user | user:*  // Public read support
  relation editor: user

  permission read = viewer + editor + owner + volume->read
  permission write = editor + owner + volume->write
  permission delete = owner + volume->write
}

definition object {
  relation bucket: bucket
  relation owner: user

  permission read = owner + bucket->read
  permission write = owner + bucket->write
  permission delete = owner + bucket->delete
}
```

#### SpiceDB Integration Code

```java
// hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/auth/SpiceDBAuthorizer.java
package org.apache.hadoop.ozone.om.auth;

import com.authzed.api.v1.*;
import com.authzed.grpcutil.BearerToken;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.hadoop.ozone.security.acl.*;

public class SpiceDBAuthorizer implements OzoneManagerAuthorizer {

  private PermissionsServiceGrpc.PermissionsServiceBlockingStub client;

  @Override
  public boolean checkAccess(IOzoneObj ozObject, RequestContext context) {
    // Convert Ozone object to SpiceDB resource
    ObjectReference resource = toSpiceDBResource(ozObject);
    String permission = mapACLTypeToPermission(context.getAclRights());

    // Build check request
    CheckPermissionRequest request = CheckPermissionRequest.newBuilder()
        .setConsistency(Consistency.newBuilder()
            .setFullyConsistent(true)
            .build())
        .setResource(resource)
        .setPermission(permission)
        .setSubject(SubjectReference.newBuilder()
            .setObject(ObjectReference.newBuilder()
                .setObjectType("user")
                .setObjectId(context.getClientUgi().getUserName())
                .build())
            .build())
        .build();

    // Execute check
    CheckPermissionResponse response = client.checkPermission(request);

    return response.getPermissionship() ==
        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION;
  }

  private ObjectReference toSpiceDBResource(IOzoneObj obj) {
    OzoneObjInfo info = (OzoneObjInfo) obj;

    switch (info.getResourceType()) {
      case VOLUME:
        return ObjectReference.newBuilder()
            .setObjectType("volume")
            .setObjectId(info.getVolumeName())
            .build();
      case BUCKET:
        return ObjectReference.newBuilder()
            .setObjectType("bucket")
            .setObjectId(info.getVolumeName() + "/" + info.getBucketName())
            .build();
      case KEY:
        return ObjectReference.newBuilder()
            .setObjectType("object")
            .setObjectId(info.getVolumeName() + "/" +
                         info.getBucketName() + "/" +
                         info.getKeyName())
            .build();
      default:
        throw new IllegalArgumentException("Unsupported resource type");
    }
  }

  private String mapACLTypeToPermission(ACLType aclType) {
    switch (aclType) {
      case READ:
      case LIST:
        return "read";
      case WRITE:
      case CREATE:
        return "write";
      case DELETE:
        return "delete";
      default:
        return "read";
    }
  }
}
```

#### Migration Strategy: Ranger → SpiceDB

**Phase 1: Dual-Write (2 weeks)**
- Write to both Ranger and SpiceDB on policy changes
- Continue reading from Ranger (authoritative)
- Verify data consistency

**Phase 2: Dual-Read Validation (2 weeks)**
- Read from both systems
- Compare results, log discrepancies
- Fix any mismatches

**Phase 3: Cutover (1 week)**
- Switch reads to SpiceDB
- Keep Ranger writes for rollback
- Monitor error rates

**Phase 4: Cleanup (1 week)**
- Stop Ranger writes
- Archive Ranger data
- Decommission Ranger cluster

---

## Part 3: Recommended Implementation Plan

### 3.1 Phase 1: OAuth2 for Admin APIs (2-3 weeks)

**Goal:** Replace Kerberos for Admin Gateway REST APIs only

**Tasks:**
1. Implement `OAuth2Filter` in Admin Gateway
2. Integrate with external IdP (Keycloak/Okta)
3. Update Admin API endpoints to use JWT claims
4. Keep Kerberos for HDFS protocol (no changes)

**Success Criteria:**
- ✅ Admin APIs accept JWT tokens
- ✅ HDFS protocol still works with Kerberos
- ✅ S3 Gateway continues using SigV4

### 3.2 Phase 2: Evaluate Authorization Needs (1 week)

**Goal:** Decide if you need to replace Ranger

**Questions to Answer:**
1. How many tenants do you have now? Expected in 1 year?
2. What's your current authorization latency p99?
3. Do you need advanced features (ReBAC, complex ABAC)?
4. Are you willing to operate SpiceDB infrastructure?

**Decision Tree:**
```
Current tenant count?
├─ < 1000 → KEEP Ranger (good enough)
├─ 1K-10K → KEEP Ranger, monitor performance
└─ > 10K → MIGRATE to SpiceDB

Authorization latency p99?
├─ < 50ms → KEEP current system
├─ 50-100ms → OPTIMIZE current system (caching)
└─ > 100ms → MIGRATE to SpiceDB

Need ReBAC (relationship-based access)?
├─ No → KEEP Ranger
└─ Yes → MIGRATE to SpiceDB
```

### 3.3 Phase 3: SpiceDB Migration (6-8 weeks, OPTIONAL)

**Only proceed if Phase 2 analysis indicates need.**

**Week 1-2:** Setup SpiceDB cluster, define schema
**Week 3-4:** Dual-write implementation
**Week 5-6:** Dual-read validation
**Week 7:** Cutover to SpiceDB
**Week 8:** Cleanup and monitoring

### 3.4 What NOT to Do

❌ **DON'T** try to remove Kerberos from HDFS protocol (breaks Spark/Hive)
❌ **DON'T** replace Ranger if you have < 1000 tenants (unnecessary complexity)
❌ **DON'T** implement custom authorization from scratch (use proven solutions)
❌ **DON'T** mix authentication protocols in the same API (confusing for clients)

---

## Part 4: Configuration Examples

### 4.1 Minimal OAuth2 Configuration (Keep Kerberos for HDFS)

```xml
<!-- ozone-site.xml - Hybrid Authentication -->
<configuration>
  <!-- Kerberos (KEEP for HDFS protocol compatibility) -->
  <property>
    <name>ozone.security.enabled</name>
    <value>true</value>
    <description>Enable Kerberos for HDFS protocol (o3fs://)</description>
  </property>

  <property>
    <name>hadoop.security.authentication</name>
    <value>kerberos</value>
  </property>

  <!-- OAuth2 (NEW for Admin APIs) -->
  <property>
    <name>ozone.admin.gateway.oauth2.enabled</name>
    <value>true</value>
    <description>Enable OAuth2 for Admin Gateway REST APIs</description>
  </property>

  <property>
    <name>ozone.admin.gateway.oauth2.issuer</name>
    <value>https://keycloak.example.com/realms/ozone</value>
  </property>

  <property>
    <name>ozone.admin.gateway.oauth2.jwks.url</name>
    <value>https://keycloak.example.com/realms/ozone/protocol/openid-connect/certs</value>
  </property>

  <!-- Authorization (KEEP Ranger or Native ACLs) -->
  <property>
    <name>ozone.acl.enabled</name>
    <value>true</value>
  </property>

  <property>
    <name>ozone.acl.authorizer.class</name>
    <value>org.apache.hadoop.ozone.security.acl.OzoneNativeAuthorizer</value>
    <description>OR use Ranger: org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer</description>
  </property>
</configuration>
```

### 4.2 SpiceDB Configuration (If Migrating from Ranger)

```xml
<!-- ozone-site.xml - SpiceDB Authorization -->
<property>
  <name>ozone.acl.authorizer.class</name>
  <value>org.apache.hadoop.ozone.om.auth.SpiceDBAuthorizer</value>
</property>

<property>
  <name>ozone.spicedb.endpoint</name>
  <value>spicedb.example.com:50051</value>
  <description>SpiceDB gRPC endpoint</description>
</property>

<property>
  <name>ozone.spicedb.preshared.key</name>
  <value>sdb_token_xxxxxx</value>
  <description>SpiceDB authentication token</description>
</property>

<property>
  <name>ozone.spicedb.cache.enabled</name>
  <value>true</value>
  <description>Enable client-side permission caching</description>
</property>

<property>
  <name>ozone.spicedb.cache.ttl</name>
  <value>300</value>
  <description>Cache TTL in seconds (5 minutes)</description>
</property>
```

---

## Part 5: Summary & Recommendations

### What You SHOULD Do

✅ **Replace Kerberos for Admin APIs** with OAuth2/OIDC
✅ **Keep Kerberos for HDFS protocol** (Spark/Hive compatibility)
✅ **Keep S3 Gateway SigV4** authentication (already modern)
✅ **Evaluate Ranger performance** before replacing it
✅ **Start with OAuth2 Phase 1** (low risk, high value)

### What You SHOULD NOT Do

❌ **Don't remove Kerberos entirely** (breaks HDFS protocol)
❌ **Don't replace Ranger prematurely** (works well for < 10K tenants)
❌ **Don't implement custom authorization** (use SpiceDB/OPA/Ranger)
❌ **Don't break backward compatibility** (support both auth methods)

### Decision Matrix

| Your Situation | Recommendation |
|----------------|---------------|
| **< 1000 tenants, simple ACLs** | Keep Native ACLs, add OAuth2 for admin APIs |
| **1K-10K tenants, using Ranger** | Keep Ranger, add OAuth2 for admin APIs |
| **> 10K tenants, need scale** | Migrate to SpiceDB, add OAuth2 |
| **Need AWS IAM compatibility** | Implement bucket policies with Native ACLs or SpiceDB |
| **Need ReBAC (shared buckets across tenants)** | Migrate to SpiceDB |

### Estimated Effort

| Phase | Effort | Risk | ROI |
|-------|--------|------|-----|
| **OAuth2 for Admin APIs** | 2-3 weeks | Low | High (modern auth, SSO) |
| **SpiceDB Migration** | 6-8 weeks | Medium | Medium (only if scaling issues) |
| **Kerberos Removal Attempt** | N/A | **DON'T DO THIS** | Negative (breaks compatibility) |

### Next Steps

1. **Week 1:** Review this design with your team
2. **Week 2:** Set up Keycloak/Okta for OAuth2 testing
3. **Week 3-4:** Implement OAuth2Filter in Admin Gateway
4. **Week 5:** Test OAuth2 integration end-to-end
5. **Week 6:** Deploy to staging environment
6. **Week 7+:** Evaluate if you need SpiceDB (based on tenant count and performance metrics)

---

## Appendix A: Kerberos Deep Dive

### Why HDFS Protocol is Tightly Coupled to Kerberos

```java
// Core Hadoop RPC - org.apache.hadoop.ipc.Server.java
public class Server {

  private void processOneRpc(byte[] buf) throws IOException {
    // ...

    // This code path is HARDCODED in Hadoop
    if (authMethod == AuthMethod.KERBEROS) {
      // SASL/GSSAPI negotiation
      saslServer = Sasl.createSaslServer(
          AuthMethod.KERBEROS.getMechanismName(),
          null,
          saslProperties,
          new SaslGssCallbackHandler()
      );
    }

    // UserGroupInformation (UGI) is populated from Kerberos ticket
    UserGroupInformation.setAuthenticationMethod(authMethod);
  }
}

// This is used throughout Ozone
// hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/ratis/OzoneManagerRatisServer.java
UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
// ugi.getUserName() → derived from Kerberos principal
// ugi.getGroupNames() → derived from Kerberos groups
```

**To remove Kerberos from HDFS protocol, you would need to:**
1. Fork Hadoop IPC library (massive undertaking)
2. Reimplement SASL authentication with OAuth2
3. Update UserGroupInformation to support JWT tokens
4. Coordinate with Spark, Hive, Presto, Flink teams to adopt changes
5. Maintain compatibility shim for years

**Verdict:** NOT WORTH IT. Keep Kerberos for HDFS protocol.

---

## Appendix B: SpiceDB vs Ranger Comparison

| Feature | Ranger | SpiceDB |
|---------|--------|---------|
| **Model** | RBAC + ABAC (policy-based) | ReBAC (relationship-based) |
| **Latency (p50)** | 10-20ms | 2-5ms |
| **Latency (p99)** | 30-50ms | 5-10ms |
| **Throughput** | 500-1K authz/sec | 10K+ authz/sec |
| **Scalability** | Vertical (single Ranger Admin) | Horizontal (distributed) |
| **Policy Limit** | 10K policies (degradation) | Millions of relationships |
| **Consistency** | Eventual (policy sync lag) | Strong (ZedTokens) |
| **UI** | Excellent (Ranger Admin UI) | Basic (CLI + API) |
| **Audit** | Excellent (Solr/HDFS) | Good (changelog) |
| **Learning Curve** | Low (familiar to Hadoop users) | Medium-High (Zanzibar model) |
| **Ops Overhead** | Medium (Ranger Admin + DB + Solr) | Medium (SpiceDB + Postgres) |
| **Hadoop Integration** | Native (designed for Hadoop) | Custom (gRPC integration) |

**When to choose Ranger:**
- < 10K tenants
- Need Ranger UI for policy management
- Already have Ranger infrastructure
- Team familiar with Ranger

**When to choose SpiceDB:**
- > 10K tenants
- Need < 10ms authorization latency
- Need ReBAC (relationship-based access)
- Willing to learn new system

---

**Document Version:** 1.0
**Last Updated:** 2025-01-19
**Ready for Implementation:** Phase 1 (OAuth2) - YES, Phase 2 (SpiceDB) - Evaluate first
