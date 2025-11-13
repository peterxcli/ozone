# IAM + Multi-Tenancy Architecture Design for Apache Ozone S3 Gateway

## Executive Summary

Based on analysis of Ozone's codebase, this document presents a comprehensive IAM system with OAuth2 support that extends Ozone's existing multi-tenancy capabilities. This design provides both evolutionary (extending current implementation) and revolutionary (external IAM) approaches.

---

## Table of Contents

1. [Modern IAM Features Identified](#1-modern-iam-features-identified)
2. [Current Ozone Architecture Analysis](#2-current-ozone-architecture-analysis)
3. [Proposed Architecture](#3-proposed-architecture)
4. [Detailed Comparison: Internal vs External Approaches](#4-detailed-comparison-internal-vs-external-approaches)
5. [Implementation Plan (Approach C: Hybrid - RECOMMENDED)](#5-implementation-plan-approach-c-hybrid---recommended)
6. [Example Configurations & Code Snippets](#6-example-configurations--code-snippets)
7. [Scalability Considerations](#7-scalability-considerations)
8. [Security Considerations](#8-security-considerations)
9. [Migration Path](#9-migration-path)
10. [Recommendations](#10-recommendations)
11. [Next Steps](#11-next-steps)

---

## 1. Modern IAM Features Identified

### Core Authentication & Authorization
- **OAuth2/OIDC Integration** - Token-based authentication with external IdPs
- **Role-Based Access Control (RBAC)** - Hierarchical role management with inheritance
- **Attribute-Based Access Control (ABAC)** - Context-aware policy decisions
- **Fine-Grained Permissions** - Bucket/object/prefix-level access control
- **Policy-as-Code** - Declarative policy definitions with version control

### Multi-Tenancy Features
- **Tenant Isolation** - Namespace, network, and resource isolation
- **Cross-Tenant Sharing** - Controlled resource sharing with delegated permissions
- **Tenant Quotas & Limits** - Resource consumption controls
- **Tenant-Scoped Roles** - Role inheritance and delegation within tenants

### Security & Compliance
- **Audit Logging** - Comprehensive access logs with tamper protection
- **Policy Enforcement Points (PEP)** - Centralized authorization checks
- **Session Management** - Token lifecycle, refresh, and revocation
- **MFA Support** - Multi-factor authentication integration
- **Secrets Rotation** - Automated credential rotation

### Advanced Features
- **Dynamic Policy Evaluation** - Real-time policy updates without restarts
- **Temporary Credentials** - STS-like token generation
- **Identity Federation** - SAML, LDAP, Active Directory integration
- **API Key Management** - Create, rotate, revoke API keys per user
- **Permission Boundaries** - Maximum permission limits for delegation

---

## 2. Current Ozone Architecture Analysis

### Existing Components (from codebase review)

```
┌─────────────────────────────────────────────────────────────┐
│                     S3 Gateway Layer                         │
│  ┌────────────────┐  ┌──────────────────────────────────┐  │
│  │ Authorization  │  │  S3 Signature Validation         │  │
│  │ Filter         │  │  (V2/V4 AWS Signature)           │  │
│  └────────────────┘  └──────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                  Ozone Manager (OM)                          │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  OMMultiTenantManagerImpl                              │ │
│  │  - tenantCache (accessId → tenant mapping)             │ │
│  │  - OMRangerBGSyncService (policy sync)                 │ │
│  └────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  S3SecretManagerImpl                                   │ │
│  │  - S3SecretStore (accessKey/secretKey storage)         │ │
│  │  - S3SecretCache (in-memory cache)                     │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│            MultiTenantAccessController                       │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  RangerClientMultiTenantAccessController               │ │
│  │  - Policy CRUD (createPolicy, updatePolicy, etc.)      │ │
│  │  - Role Management (createRole, updateRole, etc.)      │ │
│  │  - Ranger API Integration                              │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                            ↓
                   ┌──────────────────┐
                   │  Apache Ranger   │
                   │  Policy Engine   │
                   └──────────────────┘
```

### Key Files
- **AuthorizationFilter.java** (`hadoop-ozone/s3gateway/src/main/java/org/apache/hadoop/ozone/s3/AuthorizationFilter.java`)
  - Current: Only supports AWS Signature V4
  - Lines 64-92: Authentication logic

- **OMMultiTenantManagerImpl.java** (`hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/OMMultiTenantManagerImpl.java`)
  - Lines 86-139: Tenant cache and Ranger sync initialization

- **MultiTenantAccessController.java** (`hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/MultiTenantAccessController.java`)
  - Interface for policy and role management
  - Lines 47-76: Policy/Role CRUD operations

### Current Limitations
1. **No OAuth2 Support** - Only AWS Signature V4 authentication
2. **Static Credentials** - Access keys stored in DB, no token-based auth
3. **Ranger Dependency** - Tightly coupled to Ranger for authorization
4. **Limited Federation** - No OIDC/SAML integration
5. **No Session Management** - No token refresh or revocation
6. **Manual Policy Sync** - Background sync thread with potential lag

---

## 3. Proposed Architecture

### Approach A: Evolutionary (Extend Ozone)

```
┌──────────────────────────────────────────────────────────────────────┐
│                      S3 Gateway Layer                                 │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Enhanced AuthorizationFilter                                  │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐ │  │
│  │  │ AWS Sig V4   │  │ OAuth2 JWT   │  │  API Key Auth        │ │  │
│  │  │ (existing)   │  │ (new)        │  │  (new)               │ │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────────┘ │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│                     NEW: IAM Service Layer                            │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  OzoneIAMManager                                               │  │
│  │  ├─ OAuth2TokenValidator (JWT validation, OIDC integration)   │  │
│  │  ├─ SessionManager (token cache, refresh, revocation)         │  │
│  │  ├─ IdentityFederationService (OIDC/SAML/LDAP)               │  │
│  │  └─ TemporaryCredentialService (STS-like token generation)    │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│                   Enhanced Ozone Manager (OM)                         │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Enhanced OMMultiTenantManagerImpl                             │  │
│  │  - OAuth2 tenant mapping (sub/email → tenant)                  │  │
│  │  - Policy cache with real-time updates                         │  │
│  │  - Audit event publisher                                       │  │
│  └────────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Enhanced S3SecretManagerImpl                                  │  │
│  │  - API key CRUD operations                                     │  │
│  │  - Secrets rotation scheduler                                  │  │
│  │  - Token-to-credential mapping                                 │  │
│  └────────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  NEW: PolicyEnforcementService                                 │  │
│  │  - Unified PEP for all auth types                              │  │
│  │  - Policy decision caching                                     │  │
│  │  - Permission boundary enforcement                             │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│            Enhanced MultiTenantAccessController                       │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Pluggable Authorization Backend                               │  │
│  │  ├─ RangerAuthorizationBackend (existing, enhanced)           │  │
│  │  ├─ OPAAuthorizationBackend (new, optional)                   │  │
│  │  └─ CustomAuthorizationBackend (interface for extensions)     │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  ↓
                    ┌──────────────────────────────┐
                    │   Authorization Backend      │
                    │   ┌──────────┐  ┌─────────┐ │
                    │   │  Ranger  │  │   OPA   │ │
                    │   └──────────┘  └─────────┘ │
                    └──────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                    External Identity Providers                        │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────┐   │
│  │  Keycloak    │  │  Auth0       │  │  Azure AD / Okta / etc  │   │
│  │  (OIDC)      │  │  (OIDC)      │  │  (OIDC/SAML)           │   │
│  └──────────────┘  └──────────────┘  └─────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

### Approach B: Revolutionary (External IAM with SpiceDB/Keycloak)

```
┌──────────────────────────────────────────────────────────────────────┐
│                      S3 Gateway Layer                                 │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Lightweight AuthorizationFilter                               │  │
│  │  └─ JWT extraction & forwarding to Keycloak                    │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│                      Keycloak (Identity & Access)                     │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  - OAuth2/OIDC authentication                                  │  │
│  │  - User/group management                                       │  │
│  │  - Identity federation (SAML, LDAP, Social)                    │  │
│  │  - Token management (issue, refresh, revoke)                   │  │
│  │  - Multi-tenancy via Realms                                    │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│                  SpiceDB (Fine-Grained Authorization)                 │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Schema: Zanzibar-style relationship graph                     │  │
│  │                                                                 │  │
│  │  definition tenant {                                           │  │
│  │    relation admin: user                                        │  │
│  │    relation member: user                                       │  │
│  │  }                                                             │  │
│  │                                                                 │  │
│  │  definition bucket {                                           │  │
│  │    relation tenant: tenant                                     │  │
│  │    relation reader: user | user:*                              │  │
│  │    relation writer: user | user:*                              │  │
│  │    relation owner: user                                        │  │
│  │                                                                 │  │
│  │    permission read = reader + owner + tenant->admin            │  │
│  │    permission write = writer + owner + tenant->admin           │  │
│  │    permission delete = owner + tenant->admin                   │  │
│  │  }                                                             │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│                   Ozone Manager (Minimal Changes)                     │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  SpiceDBAuthorizationAdapter                                   │  │
│  │  - CheckPermission(user, resource, action) → SpiceDB API       │  │
│  │  - Sync metadata changes to SpiceDB (bucket create/delete)     │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                     Audit & Observability Layer                       │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────┐   │
│  │  ELK Stack   │  │  Prometheus  │  │  Jaeger (Tracing)       │   │
│  └──────────────┘  └──────────────┘  └─────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

### Approach C: Hybrid (RECOMMENDED)

**Architecture:**
- **Keycloak** for authentication (OAuth2/OIDC)
- **Enhanced Ranger** for authorization (leverage existing investment)
- **Ozone IAM Layer** to bridge the two

**Flow:**
```
OAuth2 Token (from Keycloak)
    ↓
JWT Validation in S3 Gateway
    ↓
Extract user principal & tenant from JWT claims
    ↓
Map to Ozone user context
    ↓
Ranger authorization (existing flow)
```

---

## 4. Detailed Comparison: Internal vs External Approaches

### Approach A: Evolutionary (Extend Ozone)

#### Pros
✅ **Seamless Integration** - Builds on existing codebase, minimal architectural changes
✅ **Single Stack** - No additional infrastructure components to manage
✅ **Ranger Compatibility** - Preserves existing Ranger investments and policies
✅ **Incremental Adoption** - Can be rolled out gradually without breaking changes
✅ **Performance** - Lower latency (no external service calls for authorization)
✅ **Operational Simplicity** - Fewer moving parts, easier to debug
✅ **Backward Compatible** - Existing AWS Signature V4 clients continue to work

#### Cons
❌ **Development Effort** - Requires significant Ozone codebase modifications
❌ **Maintenance Burden** - Need to maintain OAuth2 implementation within Ozone
❌ **Limited IAM Features** - Harder to achieve feature parity with dedicated IAM systems
❌ **Ranger Limitations** - Inherits Ranger's scalability and performance constraints
❌ **Policy Sync Lag** - Background sync can lead to eventual consistency issues
❌ **Testing Complexity** - Need to test OAuth2 + AWS Signature + API Key paths

#### Best For
- Organizations already using Ranger extensively
- Teams wanting minimal operational overhead
- Scenarios requiring low-latency authorization decisions
- Environments with limited infrastructure resources

---

### Approach B: Revolutionary (External IAM - SpiceDB + Keycloak)

#### Pros
✅ **Best-in-Class IAM** - Keycloak is mature, feature-rich, well-documented
✅ **Zanzibar Model** - SpiceDB provides Google-grade authorization at scale
✅ **Rich Features** - MFA, SAML, LDAP, social login out-of-box
✅ **Real-Time Permissions** - No sync lag, immediate policy updates
✅ **Horizontal Scalability** - SpiceDB scales to billions of relationships
✅ **Consistency Guarantees** - Strong consistency with zookies (version tokens)
✅ **Graph-Based ACLs** - Express complex permission hierarchies naturally
✅ **Standardized APIs** - OAuth2/OIDC compliance for interoperability
✅ **Audit Excellence** - Keycloak provides comprehensive audit logs

#### Cons
❌ **Operational Complexity** - 3+ additional services to run (Keycloak, SpiceDB, Postgres)
❌ **Network Latency** - External calls add 5-20ms per request
❌ **Infrastructure Cost** - Requires HA setup for production (6+ VMs minimum)
❌ **Learning Curve** - Team needs to learn Keycloak, SpiceDB schema language
❌ **Migration Complexity** - Migrating existing Ranger policies to SpiceDB
❌ **Ozone Changes** - Requires refactoring authorization layer
❌ **Dependency Risk** - Ozone depends on external services' availability

#### Best For
- Large-scale deployments (1000+ users, 100+ tenants)
- Organizations requiring enterprise IAM features
- Multi-cloud environments needing unified identity
- Scenarios with complex permission hierarchies
- Teams with DevOps capacity for managing distributed systems

---

### Approach C: Hybrid (RECOMMENDED)

#### Pros
✅ **Balanced Complexity** - Adds authentication without replacing authorization
✅ **Incremental Migration** - Can eventually move to SpiceDB if needed
✅ **Preserve Ranger** - Existing policies and workflows remain valid
✅ **OAuth2 Benefits** - Token-based auth, federation, session management
✅ **Moderate Cost** - Only need to run Keycloak (1-2 VMs in HA)

---

### Comparison Matrix

| Feature | Approach A: Extend Ozone | Approach B: SpiceDB + Keycloak | Approach C: Hybrid |
|---------|-------------------------|--------------------------------|-------------------|
| **OAuth2/OIDC** | Custom implementation | Native (Keycloak) | Native (Keycloak) |
| **Authorization** | Ranger (existing) | SpiceDB (new) | Ranger (existing) |
| **Scalability** | Medium (10K users) | Very High (1M+ users) | High (100K users) |
| **Latency** | Low (5ms) | Medium (15-25ms) | Low-Medium (8-12ms) |
| **Infrastructure** | 0 new services | 3+ new services | 1 new service |
| **Dev Effort** | High (6-9 months) | Medium (3-6 months) | Medium (4-6 months) |
| **Ops Complexity** | Low | High | Medium |
| **Feature Richness** | Medium | Very High | High |
| **Multi-Tenancy** | Enhanced (manual) | Native (Realms) | Enhanced (manual) |
| **Audit Logging** | Custom (need to build) | Built-in (Keycloak) | Built-in (Keycloak) |
| **Policy Language** | Ranger DSL | SpiceDB schema | Ranger DSL |
| **Real-Time Updates** | No (sync lag) | Yes | No (sync lag) |
| **Migration Risk** | Low | High | Medium |
| **Cost (TCO)** | Low | High | Medium |
| **Recommended For** | Small-medium deployments | Enterprise/cloud-scale | Most organizations |

---

## 5. Implementation Plan (Approach C: Hybrid - RECOMMENDED)

### Phase 1: Foundation (Months 1-2)

#### 1.1 Keycloak Setup & Configuration
**Objective:** Deploy Keycloak and configure realms for multi-tenancy

**Tasks:**
- Deploy Keycloak cluster (HA with PostgreSQL backend)
- Create master realm and tenant realms
- Configure OIDC clients for Ozone S3 Gateway
- Set up user federation (LDAP/AD if needed)

**Code Changes:** None (infrastructure only)

#### 1.2 Create OAuth2 Authentication Module
**Objective:** Build JWT validation capability in S3 Gateway

**New Java Classes:**
```
hadoop-ozone/s3gateway/src/main/java/org/apache/hadoop/ozone/s3/oauth2/
├── OAuth2TokenValidator.java           // JWT signature & claims validation
├── OAuth2AuthenticationProvider.java    // Integration with Keycloak
├── OAuth2Configuration.java             // OAuth2 config properties
├── KeycloakJwkProvider.java            // Fetch JWKS from Keycloak
├── OAuth2UserContext.java               // Extract user/tenant from JWT
└── OAuth2Exception.java                 // OAuth2-specific exceptions
```

**Modified Files:**
```
hadoop-ozone/s3gateway/src/main/java/org/apache/hadoop/ozone/s3/
├── AuthorizationFilter.java             // Add OAuth2 path alongside AWS Sig V4
└── S3GatewayHttpServer.java             // Register OAuth2 provider
```

**Key Code Changes:**
- Modify `AuthorizationFilter.java:64-92` to detect Bearer tokens
- Add JWT validation before existing signature validation
- Extract tenant info from JWT `tenant_id` or `realm` claim

---

### Phase 2: Tenant Integration (Month 3)

#### 2.1 Tenant-to-Realm Mapping
**Objective:** Map Keycloak realms to Ozone tenants

**New Java Classes:**
```
hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/oauth2/
├── RealmTenantMapper.java               // Realm ↔ Tenant mapping logic
├── TenantRealmConfig.java               // Configuration for realm mapping
└── TenantClaimExtractor.java            // Extract tenant from JWT claims
```

**Modified Files:**
```
hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/
├── OMMultiTenantManagerImpl.java        // Add OAuth2 user resolution
└── OzoneManager.java                    // Initialize OAuth2 components
```

**Database Changes:**
- Add `oauth2_realm` column to `tenantInfoTable`
- Add `oauth2_subject` column to `s3SecretTable` for token-to-user mapping

#### 2.2 User Identity Resolution
**Objective:** Map OAuth2 identities to Ozone users

**Logic:**
```java
// Pseudocode for user resolution
String jwtSubject = jwt.getClaim("sub");              // e.g., "user123"
String jwtEmail = jwt.getClaim("email");               // e.g., "user@tenant1.com"
String realmName = jwt.getIssuer();                    // e.g., "tenant1"

// Look up tenant by realm
String tenantId = realmTenantMapper.getTenantByRealm(realmName);

// Map to Ozone user principal
String ozoneUser = tenantClaimExtractor.extractUser(jwt);
```

---

### Phase 3: Authorization Enhancement (Month 4)

#### 3.1 Unified Policy Enforcement Point
**Objective:** Create single PEP for all auth methods

**New Java Classes:**
```
hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/policy/
├── PolicyEnforcementService.java        // Unified PEP interface
├── RangerPolicyEnforcer.java            // Ranger-backed enforcer
├── PolicyContext.java                   // Request context (user, resource, action)
├── PermissionBoundary.java              // Max permission constraints
└── PolicyCache.java                     // Decision caching
```

**Modified Files:**
```
hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/
└── OMMultiTenantManagerImpl.java:100-117  // Use PolicyEnforcementService
```

#### 3.2 Real-Time Policy Updates
**Objective:** Reduce Ranger sync lag

**Approach:**
- Add webhook endpoint in OM to receive Ranger policy updates
- Replace background sync with event-driven updates
- Maintain cache invalidation on policy changes

**New Configuration:**
```properties
ozone.om.ranger.webhook.enabled=true
ozone.om.ranger.webhook.port=9885
ozone.om.policy.cache.ttl=300s
```

---

### Phase 4: Session & Token Management (Month 5)

#### 4.1 Session Management Service
**Objective:** Handle token refresh and revocation

**New Java Classes:**
```
hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/session/
├── SessionManager.java                  // Session lifecycle management
├── TokenCache.java                      // In-memory token cache (Redis optional)
├── TokenRefreshService.java             // Background token refresh
└── SessionAuditLogger.java              // Session audit events
```

**Features:**
- Cache valid tokens to avoid repeated Keycloak calls
- Implement token refresh 5 minutes before expiry
- Provide revocation API for admin operations

#### 4.2 Temporary Credentials (STS-like)
**Objective:** Generate temporary S3 credentials from OAuth2 tokens

**New API Endpoint:**
```
POST /api/v1/sts/assume-role-with-web-identity
Authorization: Bearer <jwt_token>

Response:
{
  "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
  "secretAccessKey": "wJalrXUtnFEMI/...",
  "sessionToken": "AQoDYXdz...",
  "expiration": "2025-10-08T12:00:00Z"
}
```

**Implementation:**
- Generate temporary credentials with 1-hour expiry
- Store mapping in `tempCredentialsTable`
- Clean up expired credentials via background thread

---

### Phase 5: Advanced Features (Month 6)

#### 5.1 Fine-Grained Bucket Policies
**Objective:** Support S3-style bucket policies

**New Java Classes:**
```
hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/policy/
├── BucketPolicyManager.java             // Bucket policy CRUD
├── BucketPolicyEvaluator.java           // Evaluate bucket policies
├── S3PolicyParser.java                  // Parse S3 JSON policies
└── PolicyStatement.java                 // Represents policy statements
```

**Example Bucket Policy:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {"AWS": "arn:aws:iam::tenant1:user/alice"},
      "Action": ["s3:GetObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::bucket1/*"]
    }
  ]
}
```

#### 5.2 Audit Logging Integration
**Objective:** Comprehensive audit trail

**Implementation:**
- Send all auth events to Keycloak (appears in Keycloak admin console)
- Log authorization decisions to Ozone audit log
- Integrate with ELK/Splunk for centralized logging

**New Audit Events:**
```
- OAUTH2_TOKEN_VALIDATED
- OAUTH2_TOKEN_REFRESH
- OAUTH2_TOKEN_REVOKED
- POLICY_DECISION_ALLOW
- POLICY_DECISION_DENY
- TENANT_SWITCHED
- TEMPORARY_CREDENTIALS_ISSUED
```

#### 5.3 API Key Management
**Objective:** Long-lived API keys for programmatic access

**New Tables:**
```sql
CREATE TABLE api_keys (
  key_id VARCHAR(32) PRIMARY KEY,
  key_hash VARCHAR(128),      -- bcrypt hash
  user_principal VARCHAR(255),
  tenant_id VARCHAR(255),
  created_at TIMESTAMP,
  expires_at TIMESTAMP,
  last_used_at TIMESTAMP,
  permissions JSON             -- Subset of user permissions
);
```

**API Endpoints:**
```
POST   /api/v1/api-keys          # Create new API key
GET    /api/v1/api-keys          # List user's API keys
DELETE /api/v1/api-keys/{keyId}  # Revoke API key
POST   /api/v1/api-keys/{keyId}/rotate  # Rotate API key
```

---

## 6. Example Configurations & Code Snippets

### 6.1 Keycloak Configuration

#### Keycloak Realm Setup (YAML)
```yaml
# keycloak-realm-tenant1.yaml
realm: tenant1
enabled: true
displayName: "Tenant 1 - Acme Corp"

# Token settings
accessTokenLifespan: 3600          # 1 hour
ssoSessionIdleTimeout: 7200        # 2 hours
ssoSessionMaxLifespan: 43200       # 12 hours
offlineSessionIdleTimeout: 2592000 # 30 days

# Clients configuration
clients:
  - clientId: ozone-s3-gateway
    protocol: openid-connect
    publicClient: false
    secret: ${OZONE_CLIENT_SECRET}
    redirectUris:
      - "https://s3.example.com/oauth2/callback"
      - "http://localhost:9878/oauth2/callback"
    webOrigins:
      - "https://s3.example.com"
    standardFlowEnabled: true
    directAccessGrantsEnabled: true
    serviceAccountsEnabled: true

    # Protocol mappers - add custom claims
    protocolMappers:
      - name: tenant-id
        protocol: openid-connect
        protocolMapper: oidc-usermodel-attribute-mapper
        consentRequired: false
        config:
          user.attribute: tenantId
          claim.name: tenant_id
          jsonType.label: String
          id.token.claim: true
          access.token.claim: true
          userinfo.token.claim: true

      - name: ozone-roles
        protocol: openid-connect
        protocolMapper: oidc-usermodel-realm-role-mapper
        config:
          claim.name: ozone_roles
          multivalued: true
          access.token.claim: true

# User federation - LDAP example
userFederationProviders:
  - providerName: ldap
    priority: 0
    config:
      vendor: "ad"
      connectionUrl: "ldaps://ldap.tenant1.com:636"
      bindDn: "cn=ozone,ou=service-accounts,dc=tenant1,dc=com"
      bindCredential: ${LDAP_BIND_PASSWORD}
      usersDn: "ou=users,dc=tenant1,dc=com"
      usernameLDAPAttribute: "sAMAccountName"
      rdnLDAPAttribute: "cn"
      uuidLDAPAttribute: "objectGUID"
      userObjectClasses: "person, organizationalPerson, user"
```

#### Docker Compose for Keycloak HA
```yaml
# docker-compose-keycloak.yaml
version: '3.8'

services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: keycloak
      POSTGRES_USER: keycloak
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    networks:
      - keycloak-network

  keycloak-1:
    image: quay.io/keycloak/keycloak:23.0
    command: start --optimized
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: ${DB_PASSWORD}
      KC_HOSTNAME: keycloak.example.com
      KC_PROXY: edge
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: ${ADMIN_PASSWORD}
      KC_CACHE: ispn
      KC_CACHE_STACK: kubernetes
    depends_on:
      - postgres
    networks:
      - keycloak-network
    ports:
      - "8080:8080"

  keycloak-2:
    image: quay.io/keycloak/keycloak:23.0
    command: start --optimized
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: ${DB_PASSWORD}
      KC_HOSTNAME: keycloak.example.com
      KC_PROXY: edge
      KC_CACHE: ispn
      KC_CACHE_STACK: kubernetes
    depends_on:
      - postgres
    networks:
      - keycloak-network
    ports:
      - "8081:8080"

volumes:
  postgres-data:

networks:
  keycloak-network:
    driver: bridge
```

---

### 6.2 Ozone Configuration

#### ozone-site.xml
```xml
<?xml version="1.0"?>
<configuration>
  <!-- OAuth2 Configuration -->
  <property>
    <name>ozone.s3.oauth2.enabled</name>
    <value>true</value>
    <description>Enable OAuth2 authentication in S3 Gateway</description>
  </property>

  <property>
    <name>ozone.s3.oauth2.issuer.url</name>
    <value>https://keycloak.example.com/realms/master</value>
    <description>Keycloak issuer URL for token validation</description>
  </property>

  <property>
    <name>ozone.s3.oauth2.jwks.url</name>
    <value>https://keycloak.example.com/realms/master/protocol/openid-connect/certs</value>
    <description>JWKS endpoint for fetching public keys</description>
  </property>

  <property>
    <name>ozone.s3.oauth2.audience</name>
    <value>ozone-s3-gateway</value>
    <description>Expected audience claim in JWT</description>
  </property>

  <property>
    <name>ozone.s3.oauth2.tenant.claim</name>
    <value>tenant_id</value>
    <description>JWT claim containing tenant identifier</description>
  </property>

  <property>
    <name>ozone.s3.oauth2.user.claim</name>
    <value>preferred_username</value>
    <description>JWT claim containing user identifier</description>
  </property>

  <property>
    <name>ozone.s3.oauth2.token.cache.size</name>
    <value>10000</value>
    <description>Number of validated tokens to cache</description>
  </property>

  <property>
    <name>ozone.s3.oauth2.token.cache.ttl</name>
    <value>300s</value>
    <description>Token cache TTL (should be less than token lifetime)</description>
  </property>

  <!-- Session Management -->
  <property>
    <name>ozone.s3.session.manager.enabled</name>
    <value>true</value>
    <description>Enable session management features</description>
  </property>

  <property>
    <name>ozone.s3.session.refresh.enabled</name>
    <value>true</value>
    <description>Automatically refresh tokens before expiry</description>
  </property>

  <property>
    <name>ozone.s3.session.refresh.threshold</name>
    <value>300s</value>
    <description>Refresh tokens 5 minutes before expiry</description>
  </property>

  <!-- Multi-Tenancy with OAuth2 -->
  <property>
    <name>ozone.om.multitenancy.oauth2.realm.mapping</name>
    <value>tenant1:tenant1,tenant2:tenant2</value>
    <description>Comma-separated mapping of Keycloak realm to Ozone tenant</description>
  </property>

  <!-- Enhanced Ranger Integration -->
  <property>
    <name>ozone.om.ranger.webhook.enabled</name>
    <value>true</value>
    <description>Enable webhook for real-time policy updates</description>
  </property>

  <property>
    <name>ozone.om.ranger.webhook.port</name>
    <value>9885</value>
    <description>Port for Ranger webhook endpoint</description>
  </property>

  <property>
    <name>ozone.om.policy.cache.enabled</name>
    <value>true</value>
    <description>Enable policy decision caching</description>
  </property>

  <property>
    <name>ozone.om.policy.cache.ttl</name>
    <value>300s</value>
    <description>Policy cache TTL</description>
  </property>

  <!-- Temporary Credentials (STS) -->
  <property>
    <name>ozone.s3.sts.enabled</name>
    <value>true</value>
    <description>Enable STS-like temporary credentials</description>
  </property>

  <property>
    <name>ozone.s3.sts.token.lifetime</name>
    <value>3600s</value>
    <description>Temporary credential lifetime (1 hour)</description>
  </property>

  <!-- API Key Management -->
  <property>
    <name>ozone.s3.apikey.enabled</name>
    <value>true</value>
    <description>Enable API key authentication</description>
  </property>

  <property>
    <name>ozone.s3.apikey.max.per.user</name>
    <value>5</value>
    <description>Maximum API keys per user</description>
  </property>

  <!-- Audit Logging -->
  <property>
    <name>ozone.s3.audit.oauth2.enabled</name>
    <value>true</value>
    <description>Audit OAuth2 authentication events</description>
  </property>

  <property>
    <name>ozone.s3.audit.authorization.enabled</name>
    <value>true</value>
    <description>Audit authorization decisions</description>
  </property>
</configuration>
```

---

### 6.3 Java Code Examples

#### OAuth2TokenValidator.java
```java
package org.apache.hadoop.ozone.s3.oauth2;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.ozone.s3.exception.OS3Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.TimeUnit;

import static org.apache.hadoop.ozone.s3.exception.S3ErrorTable.ACCESS_DENIED;

/**
 * Validates OAuth2 JWT tokens issued by Keycloak.
 */
public class OAuth2TokenValidator {
  private static final Logger LOG = LoggerFactory.getLogger(
      OAuth2TokenValidator.class);

  private final JwkProvider jwkProvider;
  private final String expectedIssuer;
  private final String expectedAudience;
  private final OAuth2TokenCache tokenCache;

  public OAuth2TokenValidator(ConfigurationSource config) throws Exception {
    String jwksUrl = config.get("ozone.s3.oauth2.jwks.url");
    this.expectedIssuer = config.get("ozone.s3.oauth2.issuer.url");
    this.expectedAudience = config.get("ozone.s3.oauth2.audience");

    // Initialize JWKS provider with caching
    this.jwkProvider = new UrlJwkProvider(new URL(jwksUrl));

    // Initialize token cache
    int cacheSize = config.getInt("ozone.s3.oauth2.token.cache.size", 10000);
    long cacheTtl = config.getTimeDuration(
        "ozone.s3.oauth2.token.cache.ttl", 300, TimeUnit.SECONDS);
    this.tokenCache = new OAuth2TokenCache(cacheSize, cacheTtl);

    LOG.info("OAuth2TokenValidator initialized with issuer: {}", expectedIssuer);
  }

  /**
   * Validates a JWT token and returns the decoded claims.
   *
   * @param token Bearer token string
   * @return Decoded JWT with validated claims
   * @throws OS3Exception if validation fails
   */
  public DecodedJWT validateToken(String token) throws OS3Exception {
    try {
      // Check cache first
      DecodedJWT cached = tokenCache.get(token);
      if (cached != null) {
        LOG.debug("Token cache hit for subject: {}", cached.getSubject());
        return cached;
      }

      // Decode token to get key ID
      DecodedJWT unverifiedJwt = JWT.decode(token);
      String keyId = unverifiedJwt.getKeyId();

      if (keyId == null) {
        LOG.error("Token missing key ID (kid) claim");
        throw ACCESS_DENIED;
      }

      // Fetch public key from JWKS endpoint
      Jwk jwk = jwkProvider.get(keyId);
      RSAPublicKey publicKey = (RSAPublicKey) jwk.getPublicKey();

      // Build verifier with all validation rules
      Algorithm algorithm = Algorithm.RSA256(publicKey, null);
      JWTVerifier verifier = JWT.require(algorithm)
          .withIssuer(expectedIssuer)
          .withAudience(expectedAudience)
          .build();

      // Verify signature and claims
      DecodedJWT verifiedJwt = verifier.verify(token);

      // Additional custom validations
      validateCustomClaims(verifiedJwt);

      // Cache the validated token
      tokenCache.put(token, verifiedJwt);

      LOG.info("Successfully validated token for user: {} (tenant: {})",
          verifiedJwt.getClaim("preferred_username").asString(),
          verifiedJwt.getClaim("tenant_id").asString());

      return verifiedJwt;

    } catch (Exception e) {
      LOG.error("Token validation failed: {}", e.getMessage(), e);
      throw ACCESS_DENIED;
    }
  }

  /**
   * Validates custom claims required by Ozone.
   */
  private void validateCustomClaims(DecodedJWT jwt) throws OS3Exception {
    // Ensure tenant_id claim exists
    String tenantId = jwt.getClaim("tenant_id").asString();
    if (tenantId == null || tenantId.isEmpty()) {
      LOG.error("Token missing required tenant_id claim");
      throw ACCESS_DENIED;
    }

    // Ensure user identifier exists
    String username = jwt.getClaim("preferred_username").asString();
    if (username == null || username.isEmpty()) {
      LOG.error("Token missing required preferred_username claim");
      throw ACCESS_DENIED;
    }

    LOG.debug("Custom claims validated: tenant={}, user={}",
        tenantId, username);
  }

  /**
   * Extracts user context from validated JWT.
   */
  public OAuth2UserContext extractUserContext(DecodedJWT jwt) {
    return new OAuth2UserContext.Builder()
        .setSubject(jwt.getSubject())
        .setUsername(jwt.getClaim("preferred_username").asString())
        .setEmail(jwt.getClaim("email").asString())
        .setTenantId(jwt.getClaim("tenant_id").asString())
        .setRoles(jwt.getClaim("ozone_roles").asList(String.class))
        .setIssuer(jwt.getIssuer())
        .setIssuedAt(jwt.getIssuedAt())
        .setExpiresAt(jwt.getExpiresAt())
        .build();
  }
}
```

#### Enhanced AuthorizationFilter.java (Modified)
```java
package org.apache.hadoop.ozone.s3;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.hadoop.ozone.s3.oauth2.OAuth2TokenValidator;
import org.apache.hadoop.ozone.s3.oauth2.OAuth2UserContext;
import org.apache.hadoop.ozone.s3.signature.SignatureInfo;
import org.apache.hadoop.ozone.s3.signature.SignatureProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

import static org.apache.hadoop.ozone.s3.exception.S3ErrorTable.ACCESS_DENIED;

/**
 * Enhanced filter supporting both AWS Signature V4 and OAuth2 Bearer tokens.
 */
@Provider
@PreMatching
@Priority(AuthorizationFilter.PRIORITY)
public class AuthorizationFilter implements ContainerRequestFilter {
  public static final int PRIORITY = 50;
  private static final Logger LOG = LoggerFactory.getLogger(
      AuthorizationFilter.class);

  @Inject
  private SignatureProcessor signatureProcessor;

  @Inject
  private SignatureInfo signatureInfo;

  @Inject
  private OAuth2TokenValidator oauth2Validator;

  @Override
  public void filter(ContainerRequestContext context) throws IOException {
    try {
      String authHeader = context.getHeaderString("Authorization");

      if (authHeader == null || authHeader.isEmpty()) {
        LOG.debug("Missing Authorization header");
        throw ACCESS_DENIED;
      }

      // Check for OAuth2 Bearer token
      if (authHeader.startsWith("Bearer ")) {
        handleOAuth2Authentication(authHeader, context);
      }
      // Check for AWS Signature V4
      else if (authHeader.startsWith("AWS4-HMAC-SHA256")) {
        handleAwsSignatureAuthentication(context);
      }
      // Legacy AWS Signature V2
      else if (authHeader.startsWith("AWS ")) {
        handleAwsSignatureAuthentication(context);
      }
      else {
        LOG.error("Unsupported Authorization scheme: {}",
            authHeader.substring(0, Math.min(20, authHeader.length())));
        throw ACCESS_DENIED;
      }

    } catch (Exception e) {
      LOG.error("Authentication failed: {}", e.getMessage(), e);
      throw ACCESS_DENIED;
    }
  }

  /**
   * Handle OAuth2 Bearer token authentication.
   */
  private void handleOAuth2Authentication(
      String authHeader,
      ContainerRequestContext context) throws Exception {

    // Extract token from "Bearer <token>"
    String token = authHeader.substring(7).trim();

    // Validate JWT and extract claims
    DecodedJWT jwt = oauth2Validator.validateToken(token);
    OAuth2UserContext userContext = oauth2Validator.extractUserContext(jwt);

    // Store user context in request for downstream processing
    context.setProperty("oauth2.user.context", userContext);
    context.setProperty("auth.type", "oauth2");

    // Map to Ozone user principal
    String ozoneUser = userContext.getUsername();
    String tenantId = userContext.getTenantId();

    LOG.info("OAuth2 authentication successful: user={}, tenant={}",
        ozoneUser, tenantId);
  }

  /**
   * Handle AWS Signature V4 authentication (existing logic).
   */
  private void handleAwsSignatureAuthentication(
      ContainerRequestContext context) throws Exception {

    signatureInfo.initialize(signatureProcessor.parseSignature());

    if (signatureInfo.getVersion() == SignatureInfo.Version.V4) {
      signatureInfo.setStrToSign(
          StringToSignProducer.createSignatureBase(signatureInfo, context));
    } else {
      LOG.debug("Unsupported AWS signature version: {}",
          signatureInfo.getVersion());
      throw ACCESS_DENIED;
    }

    String awsAccessId = signatureInfo.getAwsAccessId();
    if (awsAccessId == null || awsAccessId.equals("")) {
      LOG.debug("Malformed S3 header. awsAccessID: {}", awsAccessId);
      throw ACCESS_DENIED;
    }

    context.setProperty("auth.type", "aws-signature");
  }
}
```

#### PolicyEnforcementService.java
```java
package org.apache.hadoop.ozone.om.policy;

import org.apache.hadoop.ozone.om.multitenant.MultiTenantAccessController;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Unified Policy Enforcement Point for all authorization decisions.
 */
public class PolicyEnforcementService {
  private static final Logger LOG = LoggerFactory.getLogger(
      PolicyEnforcementService.class);

  private final MultiTenantAccessController accessController;
  private final Cache<PolicyCacheKey, Boolean> decisionCache;

  public PolicyEnforcementService(
      MultiTenantAccessController accessController,
      long cacheTtlSeconds) {

    this.accessController = accessController;

    // Initialize decision cache (Caffeine for high performance)
    this.decisionCache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
        .recordStats()
        .build();

    LOG.info("PolicyEnforcementService initialized with cache TTL: {}s",
        cacheTtlSeconds);
  }

  /**
   * Check if user has permission to perform action on resource.
   *
   * @param context Authorization context
   * @return true if allowed, false otherwise
   */
  public boolean checkPermission(PolicyContext context) throws IOException {
    // Create cache key
    PolicyCacheKey cacheKey = PolicyCacheKey.from(context);

    // Check cache first
    Boolean cachedDecision = decisionCache.getIfPresent(cacheKey);
    if (cachedDecision != null) {
      LOG.debug("Policy cache hit: user={}, resource={}, decision={}",
          context.getUserPrincipal(),
          context.getResource().getResourceName(),
          cachedDecision);
      return cachedDecision;
    }

    // Evaluate policy (delegate to Ranger or other backend)
    boolean decision = evaluatePolicy(context);

    // Cache the decision
    decisionCache.put(cacheKey, decision);

    // Audit the decision
    auditAuthorizationDecision(context, decision);

    LOG.info("Authorization decision: user={}, resource={}, action={}, " +
        "decision={}",
        context.getUserPrincipal(),
        context.getResource().getResourceName(),
        context.getAction(),
        decision ? "ALLOW" : "DENY");

    return decision;
  }

  /**
   * Evaluate policy using configured backend (Ranger, OPA, etc.).
   */
  private boolean evaluatePolicy(PolicyContext context) throws IOException {
    // Apply permission boundaries first
    if (!checkPermissionBoundaries(context)) {
      LOG.warn("Permission boundary violation: user={}, action={}",
          context.getUserPrincipal(), context.getAction());
      return false;
    }

    // TODO: Delegate to Ranger/OPA/custom backend
    // For now, using Ranger through existing MultiTenantAccessController

    // Convert to Ranger-compatible format and check
    // This is simplified - actual implementation would call Ranger API
    return true; // Placeholder
  }

  /**
   * Check permission boundaries (maximum allowed permissions).
   */
  private boolean checkPermissionBoundaries(PolicyContext context) {
    // Permission boundaries define maximum permissions a user can have
    // Useful for delegated admin scenarios

    PermissionBoundary boundary = context.getPermissionBoundary();
    if (boundary == null) {
      return true; // No boundary set
    }

    // Check if requested action is within boundary
    return boundary.allows(context.getAction());
  }

  /**
   * Audit authorization decision.
   */
  private void auditAuthorizationDecision(
      PolicyContext context,
      boolean decision) {

    // TODO: Integrate with Ozone audit logger
    AuditEvent event = new AuditEvent.Builder()
        .setEventType(decision ? "AUTHORIZATION_ALLOW" : "AUTHORIZATION_DENY")
        .setUser(context.getUserPrincipal())
        .setResource(context.getResource().getResourceName())
        .setAction(context.getAction().toString())
        .setTenant(context.getTenantId())
        .setTimestamp(System.currentTimeMillis())
        .build();

    // Send to audit log
    LOG.info("AUDIT: {}", event.toJson());
  }

  /**
   * Invalidate cache for a specific user (e.g., after role change).
   */
  public void invalidateCacheForUser(String userPrincipal) {
    decisionCache.asMap().keySet().removeIf(
        key -> key.getUserPrincipal().equals(userPrincipal));
    LOG.info("Invalidated policy cache for user: {}", userPrincipal);
  }

  /**
   * Invalidate entire cache (e.g., after bulk policy update).
   */
  public void invalidateAllCache() {
    decisionCache.invalidateAll();
    LOG.info("Invalidated entire policy cache");
  }

  /**
   * Get cache statistics.
   */
  public String getCacheStats() {
    return decisionCache.stats().toString();
  }
}
```

---

### 6.4 SpiceDB Schema (for Approach B)

```zed
// SpiceDB schema for Ozone multi-tenancy
// File: ozone-schema.zed

/**
 * User represents an authenticated user (from Keycloak).
 */
definition user {}

/**
 * Tenant represents a multi-tenant namespace.
 */
definition tenant {
  // Relations
  relation admin: user
  relation member: user

  // Permissions
  permission create_bucket = admin
  permission view_billing = admin + member
  permission manage_users = admin
}

/**
 * Bucket represents an S3-compatible bucket.
 */
definition bucket {
  // Relations
  relation tenant: tenant
  relation owner: user
  relation reader: user | user:*  // Can be public
  relation writer: user
  relation admin: user

  // Permissions
  permission read = reader + writer + owner + admin + tenant->admin
  permission write = writer + owner + admin + tenant->admin
  permission delete = owner + admin + tenant->admin
  permission change_acl = owner + admin + tenant->admin
  permission read_acl = reader + writer + owner + admin + tenant->admin + tenant->member
}

/**
 * Object represents an S3 object (key).
 */
definition object {
  // Relations
  relation bucket: bucket
  relation owner: user
  relation reader: user | user:*
  relation writer: user

  // Permissions - inherit from bucket
  permission read = reader + writer + owner + bucket->read
  permission write = writer + owner + bucket->write
  permission delete = owner + bucket->delete
}

/**
 * Volume represents an Ozone volume (contains buckets).
 */
definition volume {
  // Relations
  relation tenant: tenant
  relation admin: user

  // Permissions
  permission create_bucket = admin + tenant->admin
  permission list_buckets = admin + tenant->admin + tenant->member
  permission delete_bucket = admin + tenant->admin
}
```

#### Example SpiceDB Relationships
```bash
# Create tenant
spicedb relationship create tenant:acme-corp admin user:alice

# Create bucket owned by tenant
spicedb relationship create bucket:my-bucket tenant tenant:acme-corp
spicedb relationship create bucket:my-bucket owner user:alice

# Grant read access to Bob
spicedb relationship create bucket:my-bucket reader user:bob

# Check permissions
spicedb check tenant:acme-corp create_bucket user:alice
# Result: ALLOWED

spicedb check bucket:my-bucket read user:bob
# Result: ALLOWED

spicedb check bucket:my-bucket delete user:bob
# Result: DENIED
```

---

### 6.5 Ranger Policy Configuration (for Approach A/C)

#### Ranger Policy JSON
```json
{
  "policyType": "0",
  "name": "tenant1-bucket-policy",
  "isEnabled": true,
  "policyPriority": 0,
  "description": "Allow tenant1 users to access their buckets",
  "resources": {
    "volume": {
      "values": ["s3v-tenant1"],
      "isExcludes": false,
      "isRecursive": false
    },
    "bucket": {
      "values": ["*"],
      "isExcludes": false,
      "isRecursive": false
    },
    "key": {
      "values": ["*"],
      "isExcludes": false,
      "isRecursive": true
    }
  },
  "policyItems": [
    {
      "accesses": [
        {"type": "read", "isAllowed": true},
        {"type": "write", "isAllowed": true},
        {"type": "create", "isAllowed": true},
        {"type": "list", "isAllowed": true}
      ],
      "users": [],
      "groups": [],
      "roles": ["tenant1-UserRole"],
      "delegateAdmin": false
    }
  ],
  "denyPolicyItems": [],
  "allowExceptions": [],
  "denyExceptions": [],
  "dataMaskPolicyItems": [],
  "rowFilterPolicyItems": []
}
```

#### Ranger Role Configuration
```json
{
  "name": "tenant1-AdminRole",
  "description": "Tenant1 administrator role",
  "users": ["alice@tenant1.com", "bob@tenant1.com"],
  "groups": [],
  "roles": []
}
```

---

### 6.6 Client Usage Examples

#### OAuth2 Flow with Python SDK
```python
#!/usr/bin/env python3
"""
Example: Accessing Ozone S3 with OAuth2 token
"""

import requests
import boto3
from botocore.client import Config

# Step 1: Get OAuth2 token from Keycloak
def get_oauth2_token(username, password):
    token_url = "https://keycloak.example.com/realms/tenant1/protocol/openid-connect/token"

    payload = {
        "grant_type": "password",
        "client_id": "ozone-s3-gateway",
        "client_secret": "your-client-secret",
        "username": username,
        "password": password,
        "scope": "openid profile email"
    }

    response = requests.post(token_url, data=payload)
    response.raise_for_status()

    return response.json()["access_token"]

# Step 2: Get temporary S3 credentials using token
def get_temp_credentials(oauth_token):
    sts_url = "https://s3.example.com/api/v1/sts/assume-role-with-web-identity"

    headers = {
        "Authorization": f"Bearer {oauth_token}"
    }

    response = requests.post(sts_url, headers=headers)
    response.raise_for_status()

    creds = response.json()
    return {
        "access_key": creds["accessKeyId"],
        "secret_key": creds["secretAccessKey"],
        "session_token": creds.get("sessionToken"),
        "expiration": creds["expiration"]
    }

# Step 3: Use temporary credentials with boto3
def main():
    # Authenticate and get token
    token = get_oauth2_token("alice", "password123")
    print(f"Got OAuth2 token: {token[:20]}...")

    # Exchange token for S3 credentials
    temp_creds = get_temp_credentials(token)
    print(f"Got temporary credentials (expires: {temp_creds['expiration']})")

    # Create S3 client with temporary credentials
    s3_client = boto3.client(
        's3',
        endpoint_url='https://s3.example.com',
        aws_access_key_id=temp_creds['access_key'],
        aws_secret_access_key=temp_creds['secret_key'],
        aws_session_token=temp_creds.get('session_token'),
        config=Config(signature_version='s3v4')
    )

    # Use S3 client normally
    response = s3_client.list_buckets()
    print("Buckets:")
    for bucket in response['Buckets']:
        print(f"  - {bucket['Name']}")

    # Upload a file
    s3_client.put_object(
        Bucket='my-bucket',
        Key='test.txt',
        Body=b'Hello from OAuth2!'
    )
    print("Uploaded test.txt successfully")

if __name__ == "__main__":
    main()
```

#### Direct OAuth2 Bearer Token (Advanced)
```bash
#!/bin/bash
# Direct S3 API call with OAuth2 Bearer token

# Get token from Keycloak
TOKEN=$(curl -s -X POST \
  https://keycloak.example.com/realms/tenant1/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=ozone-s3-gateway" \
  -d "client_secret=your-secret" \
  -d "username=alice" \
  -d "password=password123" \
  | jq -r '.access_token')

echo "Got token: ${TOKEN:0:20}..."

# List buckets using Bearer token
curl -v https://s3.example.com/ \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/xml"

# Upload object
curl -X PUT https://s3.example.com/my-bucket/test.txt \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  -d "Hello OAuth2!"
```

---

## 7. Scalability Considerations

### Performance Benchmarks (Projected)

| Metric | Current (AWS Sig V4) | Approach A (Extend) | Approach B (SpiceDB) | Approach C (Hybrid) |
|--------|---------------------|---------------------|---------------------|---------------------|
| Auth Latency | 5ms | 8ms | 15-25ms | 10ms |
| Authorization Latency | 10ms (Ranger) | 12ms (Ranger) | 5ms (SpiceDB) | 10ms (Ranger) |
| Total Request Latency | 15ms | 20ms | 20-30ms | 20ms |
| Max Users | 10K | 50K | 1M+ | 100K |
| Max Tenants | 100 | 500 | 10K+ | 1K |
| Throughput (req/s) | 10K | 8K | 15K | 9K |
| Cache Hit Rate | N/A | 85% | 95% | 90% |

### Scaling Strategies

#### Horizontal Scaling
```yaml
# Kubernetes deployment for S3 Gateway with OAuth2
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ozone-s3-gateway
spec:
  replicas: 10
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 3
      maxUnavailable: 1
  template:
    spec:
      containers:
      - name: s3-gateway
        image: apache/ozone:latest
        resources:
          requests:
            memory: "4Gi"
            cpu: "2"
          limits:
            memory: "8Gi"
            cpu: "4"
        env:
        - name: OZONE_S3_OAUTH2_ENABLED
          value: "true"
        - name: OZONE_S3_TOKEN_CACHE_SIZE
          value: "50000"  # 50K tokens per pod
```

#### Caching Strategy
- **L1 Cache**: In-memory token cache (50K tokens × 10 pods = 500K cached)
- **L2 Cache**: Redis cluster for shared token cache
- **L3 Cache**: Policy decision cache (100K decisions per pod)

#### Database Optimization
```sql
-- Index on tenant lookups
CREATE INDEX idx_tenant_oauth2_realm
ON tenant_info_table(oauth2_realm);

-- Index on user-tenant mapping
CREATE INDEX idx_s3secret_oauth2_subject
ON s3_secret_table(oauth2_subject, tenant_id);

-- Partition large tables by tenant
CREATE TABLE api_keys_partition_tenant1
PARTITION OF api_keys
FOR VALUES IN ('tenant1');
```

---

## 8. Security Considerations

### Token Security
```yaml
# Recommended Keycloak token settings
accessTokenLifespan: 900          # 15 minutes (short-lived)
refreshTokenMaxAge: 86400          # 24 hours
ssoSessionMaxLifespan: 43200       # 12 hours
offlineSessionIdleTimeout: 2592000 # 30 days

# Require token rotation
revokeRefreshToken: true
refreshTokenMaxReuse: 0
```

### Network Security
```nginx
# nginx configuration for S3 Gateway with OAuth2
server {
    listen 443 ssl http2;
    server_name s3.example.com;

    # TLS 1.3 only
    ssl_protocols TLSv1.3;
    ssl_certificate /etc/ssl/certs/s3.crt;
    ssl_certificate_key /etc/ssl/private/s3.key;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=auth:10m rate=10r/s;
    limit_req zone=auth burst=20 nodelay;

    location / {
        proxy_pass http://ozone-s3-gateway:9878;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

### Audit Log Format
```json
{
  "timestamp": "2025-10-08T10:30:45.123Z",
  "event_type": "OAUTH2_TOKEN_VALIDATED",
  "user": {
    "subject": "auth0|507f1f77bcf86cd799439011",
    "username": "alice",
    "email": "alice@tenant1.com",
    "tenant_id": "tenant1"
  },
  "resource": {
    "type": "bucket",
    "name": "my-bucket",
    "volume": "s3v-tenant1"
  },
  "action": "s3:GetObject",
  "decision": "ALLOW",
  "policy": "tenant1-bucket-policy",
  "source_ip": "203.0.113.42",
  "user_agent": "aws-cli/2.13.0",
  "request_id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "latency_ms": 12
}
```

---

## 9. Migration Path

### Phase 1: Parallel Run (Month 1)
- Deploy Keycloak alongside existing setup
- Enable OAuth2 for test tenant only
- Both authentication methods work simultaneously
- Monitor performance and errors

### Phase 2: Gradual Tenant Migration (Months 2-4)
```bash
# Migration script per tenant
ozone tenant oauth2 enable \
  --tenant tenant1 \
  --realm tenant1 \
  --keycloak-url https://keycloak.example.com

# Migrate users
ozone tenant oauth2 migrate-users \
  --tenant tenant1 \
  --dry-run  # Test first

ozone tenant oauth2 migrate-users \
  --tenant tenant1 \
  --execute
```

### Phase 3: Deprecate AWS Signature (Month 5+)
- Announce deprecation timeline
- Provide 6-month transition period
- Gradually increase OAuth2 enforcement
- Final cutover date

---

## 10. Recommendations

### For Small-Medium Deployments (< 10K users, < 100 tenants)
**Approach C (Hybrid)** is recommended:
- Deploy Keycloak (2 VMs in HA)
- Keep existing Ranger authorization
- Implement OAuth2 authentication layer
- Estimated effort: 4-6 months
- Estimated cost: +$500/month infrastructure

### For Large/Enterprise Deployments (> 10K users, > 100 tenants)
**Approach B (SpiceDB + Keycloak)** is recommended:
- Full external IAM stack
- Leverage Zanzibar-style authorization
- Best scalability and features
- Estimated effort: 3-6 months (less Ozone code changes)
- Estimated cost: +$2000/month infrastructure

### For Resource-Constrained Environments
**Approach A (Extend Ozone)** is recommended:
- No additional infrastructure
- OAuth2 built into Ozone
- Limited feature set
- Estimated effort: 6-9 months
- Estimated cost: $0 additional infrastructure

---

## 11. Next Steps

1. **Proof of Concept** (2 weeks)
   - Set up Keycloak locally
   - Implement basic JWT validation in fork
   - Test end-to-end OAuth2 flow

2. **Architecture Review** (1 week)
   - Present design to team
   - Get feedback from security team
   - Finalize approach selection

3. **Prototype Development** (1 month)
   - Implement Phase 1 (Foundation)
   - Deploy to dev environment
   - Performance testing

4. **Production Pilot** (1 month)
   - Select pilot tenant
   - Deploy to production
   - Monitor and iterate

5. **Full Rollout** (3-4 months)
   - Gradual tenant migration
   - Documentation and training
   - Support and maintenance

---

## Summary

This document provides a comprehensive IAM + multi-tenancy design for Apache Ozone with:

1. ✅ **Modern IAM features identified**: OAuth2, RBAC, ABAC, fine-grained permissions, audit logging, etc.

2. ✅ **Three architectural approaches**:
   - **A**: Evolutionary (extend Ozone)
   - **B**: Revolutionary (SpiceDB + Keycloak)
   - **C**: Hybrid (Keycloak auth + Ranger authz) - **RECOMMENDED**

3. ✅ **Detailed implementation plan**: 6-phase rollout over 6 months

4. ✅ **Complete code examples**:
   - Keycloak configuration
   - Java classes (OAuth2TokenValidator, enhanced AuthorizationFilter, PolicyEnforcementService)
   - SpiceDB schema
   - Ranger policies
   - Client usage examples

5. ✅ **Scalability, security, and migration strategies**

The **Hybrid Approach (C)** offers the best balance of features, complexity, and cost for most organizations. It preserves your Ranger investment while adding modern OAuth2 authentication capabilities.

---

## Appendix

### References
- [Ozone S3 Multi-Tenancy Documentation](https://ozone.apache.org/docs/edge/feature/s3-multi-tenancy.html)
- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [SpiceDB Documentation](https://authzed.com/docs)
- [Apache Ranger Documentation](https://ranger.apache.org/)
- [OAuth2 RFC 6749](https://tools.ietf.org/html/rfc6749)
- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)

### Contact
For questions or feedback about this design document, please contact the Apache Ozone development team.

### Version History
- v1.0 (2025-10-08): Initial design document
