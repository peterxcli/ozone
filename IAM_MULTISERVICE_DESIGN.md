# Comprehensive IAM Design for Apache Ozone in Multi-Service Datacenters

**Version:** 3.0
**Date:** 2025-10-15
**Status:** Unified Design Document

---

## Executive Summary

This document provides a complete IAM (Identity and Access Management) design for Apache Ozone S3 Gateway in **multi-service datacenters** where:
- **BSS (Business Support System)** manages customer onboarding for multiple services (Storage, GPU, VM, Containers)
- **BSS has its own RBAC system** for managing users and permissions
- **Storage team** needs to integrate with BSS's RBAC while maintaining control over S3-specific permissions

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **BSS Integration Pattern** | Pull-Based or Event-Stream | Works for multi-service datacenters (Storage + GPU + VM + Containers) |
| **BSS RBAC Integration** | Dual-Layer Authorization | BSS enforces coarse-grained access, Storage enforces fine-grained S3 permissions |
| **Storage Authorization** | Ranger (MVP) → SpiceDB (scale) | Start with Ranger (2-3 weeks), migrate to SpiceDB for 1M+ tenants |
| **Authentication** | OAuth2/OIDC (Admin) + SigV4 (S3) | Industry standard for web/API + AWS SDK compatibility |
| **AWS Compatibility** | Bucket policies, ACLs, condition keys | S3 API parity for existing applications |

---

## Table of Contents

1. [Problem Statement & Architecture Overview](#problem-statement--architecture-overview)
2. [Team Responsibilities & BSS RBAC Integration](#team-responsibilities--bss-rbac-integration)
3. [Multi-Service Integration Patterns](#multi-service-integration-patterns)
4. [Storage IAM Implementation (Ranger MVP)](#storage-iam-implementation-ranger-mvp)
5. [SpiceDB Migration for Scale](#spicedb-migration-for-scale)
6. [AWS S3 Compatibility Layer](#aws-s3-compatibility-layer)
7. [Security & Compliance](#security--compliance)
8. [Implementation Roadmap](#implementation-roadmap)

---

## Problem Statement & Architecture Overview

### Your Datacenter Context

Your organization has a **multi-service datacenter** managed by two teams:

1. **BSS Team:**
   - Manages customer onboarding and billing
   - Provides self-service portal for customers
   - **Has their own RBAC system** (e.g., roles like CustomerAdmin, Developer, Viewer, ComplianceOfficer)
   - Provisions resources across **multiple services**: Storage (Ozone), GPU, VM, Containers

2. **Storage Team (You):**
   - Manages Apache Ozone and S3 Gateway
   - Needs fine-grained S3 authorization (bucket policies, ACLs, object permissions)
   - Already has Ranger/SpiceDB for authorization

### Key Challenges

1. **BSS RBAC vs Storage Authorization:** BSS has its own RBAC, but S3 needs fine-grained permissions (bucket-level, object-level, path-based). How do they coexist?
2. **Multi-Service Coordination:** BSS provisions Storage + GPU + VM + Containers. Storage shouldn't be called directly by BSS (tight coupling).
3. **Permission Depth:** How deep into S3 permissions should BSS's RBAC go? Should BSS differentiate `s3:GetObject` vs `s3:PutObject`?
4. **Single Source of Truth:** Who owns what data? Customer accounts? User identities? Permissions?
5. **Integration Complexity:** How to integrate BSS's RBAC with Storage's Ranger/SpiceDB without duplication?

### Recommended Solution: Dual-Layer Authorization

```
┌─────────────────────────────────────────────────────────────────┐
│                  DUAL-LAYER AUTHORIZATION MODEL                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  LAYER 1: BSS RBAC (Coarse-Grained)                            │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  BSS manages:                                             │  │
│  │  - Customer accounts (who can access datacenter?)         │  │
│  │  - User identities (SSO, MFA)                             │  │
│  │  - High-level roles:                                      │  │
│  │    * CustomerAdmin: Can manage all resources in customer  │  │
│  │    * Developer: Can use storage/GPU/VM                    │  │
│  │    * Viewer: Read-only access                             │  │
│  │    * ComplianceOfficer: Can access compliance data only   │  │
│  │  - Service entitlements:                                  │  │
│  │    * customer-acme: storage=yes, gpu=yes, vm=no          │  │
│  │  - Coarse-grained deny rules:                             │  │
│  │    * "Developer cannot access /compliance/* buckets"      │  │
│  │    * "Suspended customers cannot access any service"      │  │
│  └───────────────────────────────────────────────────────────┘  │
└───────────────────────┬─────────────────────────────────────────┘
                        │ Delegates fine-grained control to services
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│  LAYER 2: Storage Authorization (Fine-Grained S3)              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Storage (Ranger/SpiceDB) manages:                        │  │
│  │  - Bucket-level permissions:                              │  │
│  │    * alice can s3:CreateBucket in tenant acme-123         │  │
│  │  - Object-level permissions:                              │  │
│  │    * bob can s3:GetObject on bucket-A/*                   │  │
│  │    * bob can s3:PutObject on bucket-B/logs/*              │  │
│  │  - Path-based access:                                     │  │
│  │    * charlie can s3:GetObject on bucket-C/public/*        │  │
│  │  - Bucket policies (AWS-compatible):                      │  │
│  │    * "AllUsers can s3:GetObject on bucket-D/public/*"     │  │
│  │  - Bucket ACLs:                                           │  │
│  │    * bucket-E: owner=alice, public-read                   │  │
│  │  - Conditional access:                                    │  │
│  │    * Allow s3:GetObject if sourceIP in 10.0.0.0/8         │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

Authorization Flow:
1. User makes S3 request (e.g., GET /bucket-A/file.txt)
2. Storage validates JWT from BSS (Layer 1 check)
   - Is user active? Is customer active? Is storage entitled?
   - Does BSS deny rule apply? (e.g., /compliance/* blocked for Developer)
3. If Layer 1 allows, Storage checks Ranger/SpiceDB (Layer 2)
   - Does user have s3:GetObject on bucket-A/file.txt?
4. If both layers allow → ALLOW, else → DENY
```

**Key Principle:**
- **BSS RBAC:** "Can this user access storage at all? Are there compliance restrictions?"
- **Storage Authorization:** "Can this user perform this specific S3 action on this specific object?"

---

## Team Responsibilities & BSS RBAC Integration

### Responsibility Split

| Concern | BSS Team Owns | Storage Team Owns | Integration Point |
|---------|---------------|-------------------|-------------------|
| **Customer Accounts** | ✅ Create, manage, bill customers | | BSS → Storage: customer_id in JWT |
| **User Identity** | ✅ User directory (LDAP/AD/Keycloak), SSO, MFA | | BSS → Storage: JWT validation |
| **BSS Roles** | ✅ CustomerAdmin, Developer, Viewer, ComplianceOfficer | | BSS → Storage: roles in JWT claims |
| **Service Entitlements** | ✅ Which services customer can use (storage, gpu, vm) | | BSS → Storage: entitlements in customer API |
| **Coarse-Grained Deny** | ✅ "Developer cannot access /compliance/*" | | BSS → Storage: deny policies via API or JWT |
| **Tenant/Namespace** | Requests creation | ✅ Volume creation (s3v-{customerId}) | Storage auto-creates on first request |
| **Storage Quotas** | Sets commercial limit (1TB, 10TB) | ✅ Enforces quota in Ozone | BSS → Storage: quota in customer API |
| **S3 Bucket Permissions** | | ✅ s3:CreateBucket, DeleteBucket, GetBucketAcl | |
| **S3 Object Permissions** | | ✅ s3:GetObject, PutObject, DeleteObject | |
| **S3 Bucket Policies** | | ✅ AWS-compatible policy CRUD & evaluation | |
| **S3 Bucket ACLs** | | ✅ Canned ACLs (private, public-read) | |
| **Path-Based Access** | | ✅ /logs/*, /data/* prefix permissions | |
| **SigV4 Authentication** | | ✅ AWS SDK signature validation | |
| **S3 Access Keys** | Requests generation via portal | ✅ Generate keys (tenantId$userId format) | BSS calls Storage API |
| **Audit Logs** | Views aggregated metrics | ✅ Captures S3 API call logs | Storage → BSS: metrics API |
| **Billing/Metering** | ✅ Invoicing based on usage | Emits usage metrics | Storage → BSS: metrics API |

---

### How BSS RBAC Integrates with Storage

BSS has its own RBAC system with roles like:
- **CustomerAdmin:** Full control over customer's resources (all services)
- **Developer:** Can use services (storage, GPU, VM) for development
- **Viewer:** Read-only access to dashboards and logs
- **ComplianceOfficer:** Can access compliance-related data only
- **Finance:** Can view billing and usage metrics

**Integration Strategy:** Role Mapping with Coarse-Grained Deny

```
┌─────────────────────────────────────────────────────────────────┐
│  BSS RBAC System                                                │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  User: alice@acme.com                                     │  │
│  │  Customer: acme-123                                       │  │
│  │  BSS Roles: [CustomerAdmin, ComplianceOfficer]           │  │
│  │  Permissions in BSS:                                      │  │
│  │  - CanManageUsers (add/remove users)                     │  │
│  │  - CanViewBilling                                         │  │
│  │  - CanAccessComplianceData                               │  │
│  │  - CanUseStorage, CanUseGPU, CanUseVM                    │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  High-Level Deny Policies (BSS owns)                     │  │
│  │  - Developer role: DENY access to /compliance/* buckets  │  │
│  │  - Viewer role: DENY all write operations (PUT, DELETE)  │  │
│  │  - Suspended customers: DENY all access                  │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────┬───────────────────────────────────────────┘
                      │ Issues JWT with claims
                      ▼
          ┌──────────────────────────────┐
          │  JWT Token                   │
          │  {                           │
          │    "sub": "alice@acme.com",  │
          │    "customer_id": "acme-123",│
          │    "bss_roles": [            │
          │      "CustomerAdmin",        │
          │      "ComplianceOfficer"     │
          │    ],                        │
          │    "entitlements": {         │
          │      "storage": true,        │
          │      "gpu": true             │
          │    }                         │
          │  }                           │
          └──────────────┬───────────────┘
                         │ User makes S3 request with JWT
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  Storage (Ozone)                                                │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  OAuth2Filter validates JWT                               │  │
│  │  - Check signature (BSS's public key)                     │  │
│  │  - Extract customer_id, bss_roles                         │  │
│  │  - Check BSS deny policies (optional, via API or cache)   │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Role Mapping Service                                     │  │
│  │  Maps BSS roles → Ozone permissions:                      │  │
│  │  - CustomerAdmin → Tenant Admin (s3:*)                    │  │
│  │  - Developer → Tenant User (s3:Get*, Put*, Delete*)       │  │
│  │  - Viewer → Read-Only (s3:Get*, s3:List*)                 │  │
│  │  - ComplianceOfficer → Compliance Bucket Access           │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Ranger/SpiceDB Authorization                             │  │
│  │  - Check mapped permissions against Ranger policies       │  │
│  │  - Apply bucket policies, ACLs, path-based rules          │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**Example Role Mapping Configuration:**

```yaml
# hadoop-ozone/admin-gateway/conf/bss-role-mapping.yaml
bss_role_mappings:
  CustomerAdmin:
    ozone_role: tenant-admin
    ranger_role: "{tenantId}-AdminRole"
    permissions:
      - s3:*                          # All S3 operations
      - admin:ManageTenant
      - admin:ManageUsers
      - admin:ViewAuditLogs
    description: Full control over tenant resources

  Developer:
    ozone_role: tenant-user
    ranger_role: "{tenantId}-UserRole"
    permissions:
      - s3:GetObject
      - s3:PutObject
      - s3:DeleteObject
      - s3:ListBucket
      - s3:CreateBucket
      - s3:DeleteBucket
    deny_rules:                       # BSS-level deny overrides
      - resource: "*/compliance/*"    # Cannot access compliance buckets
        reason: "Compliance data restricted to ComplianceOfficer role"
    description: Read/write access to buckets

  Viewer:
    ozone_role: tenant-viewer
    ranger_role: "{tenantId}-ViewerRole"
    permissions:
      - s3:GetObject
      - s3:ListBucket
      - s3:GetBucketAcl
    description: Read-only access

  ComplianceOfficer:
    ozone_role: tenant-compliance
    ranger_role: "{tenantId}-ComplianceRole"
    permissions:
      - s3:GetObject                  # Only on compliance buckets
      - s3:ListBucket
    allowed_prefixes:                 # Restricts access to specific paths
      - "*/compliance/*"
      - "*/audit-logs/*"
    description: Access to compliance and audit data only

  Finance:
    ozone_role: tenant-finance
    ranger_role: "{tenantId}-FinanceRole"
    permissions:
      - admin:ViewMetrics              # Can view usage metrics
      - admin:ViewBilling
    description: Billing and usage metrics access only (no S3 access)
```

---

### BSS Coarse-Grained Deny Policies (Optional)

If BSS wants to enforce compliance rules beyond role mapping, Storage can query BSS for deny policies:

```java
// hadoop-ozone/ozone-manager/.../BSSPolicyClient.java
public class BSSPolicyClient {
  private final RestClient bssClient;
  private final Cache<String, BSSPolicy> policyCache;

  public BSSPolicy getCustomerPolicy(String customerId) {
    return policyCache.get(customerId, () -> {
      String url = bssBaseUrl + "/policies/customer/" + customerId;
      return bssClient.get(url, BSSPolicy.class);
    });
  }
}

// Authorization integration
public boolean checkAccess(String tenantId, String action, String resource, String userId) {
  // 1. Check BSS deny policies (coarse-grained) - OPTIONAL
  if (bssIntegrationEnabled) {
    BSSPolicy bssPolicy = bssPolicyClient.getCustomerPolicy(tenantId);
    if (bssPolicy.hasDenyRule(action, resource, userId)) {
      LOG.warn("BSS policy denied access: tenant={}, action={}, resource={}",
          tenantId, action, resource);
      return false;  // BSS deny overrides everything
    }
  }

  // 2. Check Ranger/SpiceDB allow policies (fine-grained)
  return rangerAuthz.checkAccess(tenantId, action, resource, userId);
}
```

**BSS Policy Example:**

```json
{
  "customer_id": "acme-123",
  "tier": "enterprise",
  "deny_rules": [
    {
      "sid": "DenyComplianceBucketsForDevelopers",
      "effect": "Deny",
      "action": "s3:*",
      "resource": "arn:aws:s3:::*/compliance/*",
      "condition": {
        "StringEquals": {
          "bss:role": "Developer"
        }
      },
      "reason": "Compliance data restricted to ComplianceOfficer role"
    },
    {
      "sid": "DenyDeleteInProduction",
      "effect": "Deny",
      "action": "s3:DeleteObject",
      "resource": "arn:aws:s3:::production/*",
      "condition": {
        "StringNotEquals": {
          "bss:role": "CustomerAdmin"
        }
      },
      "reason": "Only CustomerAdmin can delete production data"
    }
  ]
}
```

---

## Multi-Service Integration Patterns

Since your datacenter provides **multiple services** (Storage, GPU, VM, Containers), BSS should NOT call each service's API directly. Instead, use one of these patterns:

### Pattern Comparison

| Pattern | BSS Complexity | Storage Complexity | Latency | Best For |
|---------|----------------|-------------------|---------|----------|
| **Pull-Based** | Low (expose GET API) | Low (HTTP client) | First request: +50-200ms | Simple setup, < 5 services |
| **Event-Stream** | Medium (Kafka producer) | Medium (Kafka consumer) | Eventual (1-5s lag) | 10+ services, already have Kafka |
| **Hybrid** | Medium | Medium | Best of both | High reliability requirement |

---

### Pattern A: Pull-Based (Recommended for Simplicity)

**How it works:**
1. BSS creates customer in its own database with entitlements for all services
2. BSS issues OAuth2 JWT with `customer_id` claim
3. Storage pulls customer metadata **on-demand** when first S3 request arrives
4. Storage creates tenant lazily (Just-In-Time provisioning)
5. GPU, VM, Container services do the same independently

```
┌─────────────────────────────────────────────────────────────────┐
│  BSS (Business Support System)                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Central Customer Registry (Database)                     │  │
│  │  Customers:                                               │  │
│  │  - id: acme-123                                           │  │
│  │  - name: Acme Corp                                        │  │
│  │  - tier: enterprise                                       │  │
│  │  - status: active                                         │  │
│  │  - admin_email: admin@acme.com                            │  │
│  │  - entitlements:                                          │  │
│  │    * storage: {enabled: true, quotaGB: 1000}              │  │
│  │    * gpu: {enabled: true, gpuHours: 500}                  │  │
│  │    * vm: {enabled: false}                                 │  │
│  │    * container: {enabled: true, maxPods: 50}              │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  BSS REST API (READ-ONLY for services)                   │  │
│  │  GET /api/v1/customers/{customerId}                       │  │
│  │  GET /api/v1/users/{userId}                               │  │
│  │  GET /api/v1/policies/customer/{customerId} (optional)    │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  OAuth2/OIDC Provider (Keycloak)                          │  │
│  │  Issues JWTs with:                                        │  │
│  │  - customer_id                                            │  │
│  │  - bss_roles (from BSS RBAC)                              │  │
│  │  - entitlements (storage: true, gpu: true)                │  │
│  └───────────────────────────────────────────────────────────┘  │
└────────────────────┬────────────────────────────────────────────┘
                     │ Services pull customer metadata when needed
                     │
      ┌──────────────┼──────────────┬─────────────┬──────────────┐
      │              │              │             │              │
      ▼              ▼              ▼             ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ Storage  │  │   GPU    │  │    VM    │  │Container │  │  Other   │
│ (Ozone)  │  │ Service  │  │ Service  │  │ Service  │  │ Services │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │
     │ On first request from user alice@acme.com:           │
     │ 1. Validate JWT (issued by BSS Keycloak)             │
     │ 2. Extract customer_id from JWT                       │
     │ 3. Call BSS API: GET /customers/acme-123             │
     │ 4. Check entitlements.storage == true                │
     │ 5. Check BSS deny policies (optional)                │
     │ 6. Create local tenant if not exists                 │
     │ 7. Cache customer metadata (15 min TTL)              │
     │                                                       │
     └───────────────────────────────────────────────────────┘
```

**Implementation:**

```java
// hadoop-ozone/admin-gateway/src/main/java/org/apache/ozone/admin/bss/BSSClient.java
package org.apache.ozone.admin.bss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.fluent.Request;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public class BSSClient {
  private static final Logger LOG = LoggerFactory.getLogger(BSSClient.class);

  private final String bssBaseUrl;
  private final String serviceToken;  // M2M token for Storage → BSS
  private final ObjectMapper mapper = new ObjectMapper();

  // Cache customer metadata to avoid excessive BSS calls
  private final Cache<String, Customer> customerCache = CacheBuilder.newBuilder()
      .expireAfterWrite(15, TimeUnit.MINUTES)
      .maximumSize(10000)
      .build();

  @Inject
  public BSSClient(ConfigurationSource conf) {
    this.bssBaseUrl = conf.get("ozone.bss.api.url", "https://bss.example.com/api/v1");
    this.serviceToken = conf.get("ozone.bss.service.token");
  }

  public Customer getCustomer(String customerId) throws IOException {
    // Check cache first
    Customer cached = customerCache.getIfPresent(customerId);
    if (cached != null) {
      LOG.debug("Customer cache hit: {}", customerId);
      return cached;
    }

    // Fetch from BSS
    String url = bssBaseUrl + "/customers/" + customerId;
    LOG.info("Fetching customer from BSS: {}", url);

    String response = Request.Get(url)
        .addHeader("Authorization", "Bearer " + serviceToken)
        .execute()
        .returnContent()
        .asString();

    Customer customer = mapper.readValue(response, Customer.class);

    // Cache for future requests
    customerCache.put(customerId, customer);

    return customer;
  }

  public static class Customer {
    private String id;
    private String name;
    private String tier;
    private String status;
    private String adminEmail;
    private Map<String, Map<String, Object>> entitlements;

    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public String getTier() { return tier; }
    public String getStatus() { return status; }
    public String getAdminEmail() { return adminEmail; }
    public Map<String, Map<String, Object>> getEntitlements() { return entitlements; }
  }
}
```

```java
// hadoop-ozone/admin-gateway/.../OAuth2Filter.java (Enhanced)
@Override
public void filter(ContainerRequestContext requestContext) {
  // 1. Validate JWT from BSS
  DecodedJWT jwt = validateJWT(requestContext);
  String userId = jwt.getSubject();  // alice@acme.com
  String customerId = jwt.getClaim("customer_id").asString();  // acme-123
  String[] bssRoles = jwt.getClaim("bss_roles").asArray(String.class);  // [CustomerAdmin]

  // 2. Check if tenant exists locally
  String tenantId = "customer-" + customerId;

  if (!tenantService.tenantExists(tenantId)) {
    LOG.info("Tenant not found locally, pulling from BSS: {}", tenantId);

    // 3. Pull customer metadata from BSS
    BSSClient.Customer customer = bssClient.getCustomer(customerId);

    // 4. Check customer status
    if ("suspended".equals(customer.getStatus()) || "deleted".equals(customer.getStatus())) {
      throw new ForbiddenException("Customer account is " + customer.getStatus());
    }

    // 5. Check storage entitlement
    Map<String, Object> storageEnt = customer.getEntitlements().get("storage");
    if (storageEnt == null || !(Boolean) storageEnt.get("enabled")) {
      throw new ForbiddenException("Storage not enabled for this customer");
    }

    // 6. Create tenant on-demand (lazy provisioning)
    long quotaGB = ((Number) storageEnt.get("quotaGB")).longValue();
    long quotaBytes = quotaGB * 1024 * 1024 * 1024;

    tenantService.createTenant(
      tenantId,
      customer.getAdminEmail(),
      quotaBytes
    );

    LOG.info("Tenant auto-provisioned: {}", tenantId);
  }

  // 7. Check BSS deny policies (optional)
  if (bssIntegrationEnabled) {
    String requestPath = requestContext.getUriInfo().getPath();
    if (hasBSSDenyRule(customerId, bssRoles, requestPath)) {
      throw new ForbiddenException("BSS policy denies access to this resource");
    }
  }

  // 8. Set context for downstream authorization
  TenantContext.setTenantId(tenantId);
  TenantContext.setUserId(userId);
  TenantContext.setBSSRoles(bssRoles);
}

private boolean hasBSSDenyRule(String customerId, String[] bssRoles, String requestPath) {
  // Check role mapping deny rules
  for (String role : bssRoles) {
    RoleMapping mapping = roleMapper.getMapping(role);
    if (mapping.hasDenyRule(requestPath)) {
      return true;
    }
  }

  // Optionally: Query BSS for dynamic deny policies
  if (bssPolicyClient != null) {
    BSSPolicy policy = bssPolicyClient.getCustomerPolicy(customerId);
    return policy.hasDenyRule(requestPath, bssRoles);
  }

  return false;
}
```

**Configuration:**

```xml
<!-- ozone-site.xml -->
<property>
  <name>ozone.bss.api.url</name>
  <value>https://bss.example.com/api/v1</value>
  <description>BSS API base URL for pulling customer metadata</description>
</property>

<property>
  <name>ozone.bss.service.token</name>
  <value>eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...</value>
  <description>Service account token for Storage → BSS API calls</description>
</property>

<property>
  <name>ozone.bss.customer.cache.ttl</name>
  <value>900</value>
  <description>Customer metadata cache TTL in seconds (default: 15 min)</description>
</property>

<property>
  <name>ozone.bss.integration.enabled</name>
  <value>true</value>
  <description>Enable BSS RBAC integration with deny policy checking</description>
</property>
```

**Pros:**
- ✅ Simple (just HTTP client, no message broker)
- ✅ Works for multi-service (GPU, VM do the same)
- ✅ Single source of truth (BSS customer registry)
- ✅ Auto-provisions tenants when needed
- ✅ BSS RBAC integrated via JWT claims and role mapping

**Cons:**
- ⚠️ First request latency (+50-200ms, mitigated by caching)
- ⚠️ BSS must be available at runtime (mitigated by caching)

---

### Pattern B: Event-Stream (Recommended for Scale)

**How it works:**
1. BSS publishes `customer.created` event to Kafka/RabbitMQ
2. Each service (Storage, GPU, VM, Containers) subscribes independently
3. Each service creates its own tenant when it receives the event
4. No direct API calls between BSS and services

```
┌─────────────────────────────────────────────────────────────────┐
│  BSS (Business Support System)                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Customer Database                                        │  │
│  └────────────────┬──────────────────────────────────────────┘  │
│                   │                                              │
│  ┌────────────────▼──────────────────────────────────────────┐  │
│  │  Event Publisher                                          │  │
│  │  Events:                                                  │  │
│  │  - customer.created                                       │  │
│  │  - customer.updated (tier change, quota change)           │  │
│  │  - customer.suspended                                     │  │
│  │  - customer.deleted                                       │  │
│  │  - user.added_to_customer                                 │  │
│  │  - user.role_changed (BSS RBAC role update)               │  │
│  └────────────────┬──────────────────────────────────────────┘  │
└───────────────────┼──────────────────────────────────────────────┘
                    │
                    ▼ Publishes to
┌─────────────────────────────────────────────────────────────────┐
│         Kafka / RabbitMQ / NATS / Cloud Pub/Sub                 │
│  Topics:                                                        │
│  - bss.customer.lifecycle                                       │
│  - bss.user.lifecycle                                           │
└───┬─────────────┬─────────────┬─────────────┬───────────────────┘
    │             │             │             │
    │ Subscribe   │ Subscribe   │ Subscribe   │ Subscribe
    │             │             │             │
    ▼             ▼             ▼             ▼
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│ Storage  │ │   GPU    │ │    VM    │ │Container │
│ Consumer │ │ Consumer │ │ Consumer │ │ Consumer │
└────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
     │            │            │            │
     │ On customer.created event:         │
     │ 1. Read event payload               │
     │ 2. Check if service is entitled     │
     │ 3. Create local tenant if enabled   │
     │ 4. ACK event                        │
     │                                      │
     └──────────────────────────────────────┘
```

**Event Schema:**

```json
{
  "event_type": "customer.created",
  "event_id": "evt-123456",
  "timestamp": "2025-10-15T10:30:00Z",
  "customer": {
    "id": "acme-123",
    "name": "Acme Corp",
    "tier": "enterprise",
    "status": "active",
    "admin_email": "admin@acme.com",
    "entitlements": {
      "storage": {
        "enabled": true,
        "quotaGB": 1000
      },
      "gpu": {
        "enabled": true,
        "gpuHours": 500
      },
      "vm": {
        "enabled": false
      },
      "container": {
        "enabled": true,
        "maxPods": 50
      }
    },
    "bss_roles": [
      {
        "name": "CustomerAdmin",
        "users": ["admin@acme.com"]
      },
      {
        "name": "Developer",
        "users": ["alice@acme.com", "bob@acme.com"]
      }
    ]
  }
}
```

**Implementation:**

```java
// hadoop-ozone/admin-gateway/.../BSSEventConsumer.java
package org.apache.ozone.admin.bss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.*;
import org.apache.ozone.admin.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

public class BSSEventConsumer implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(BSSEventConsumer.class);

  private final KafkaConsumer<String, String> consumer;
  private final TenantService tenantService;
  private final RoleMapper roleMapper;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public BSSEventConsumer(ConfigurationSource conf, TenantService tenantService, RoleMapper roleMapper) {
    this.tenantService = tenantService;
    this.roleMapper = roleMapper;

    Properties props = new Properties();
    props.put("bootstrap.servers", conf.get("ozone.bss.kafka.brokers", "localhost:9092"));
    props.put("group.id", "ozone-storage-service");
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    props.put("enable.auto.commit", "false");  // Manual commit for reliability

    this.consumer = new KafkaConsumer<>(props);
    consumer.subscribe(Collections.singletonList("bss.customer.lifecycle"));
  }

  @Override
  public void run() {
    LOG.info("Starting BSS event consumer...");

    while (true) {
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

      for (ConsumerRecord<String, String> record : records) {
        try {
          processEvent(record.value());
          consumer.commitSync();  // ACK event
        } catch (Exception e) {
          LOG.error("Failed to process event: {}", record.value(), e);
          // Retry or send to dead-letter queue
        }
      }
    }
  }

  private void processEvent(String eventJson) throws Exception {
    Map<String, Object> event = mapper.readValue(eventJson, Map.class);
    String eventType = (String) event.get("event_type");

    switch (eventType) {
      case "customer.created":
        handleCustomerCreated(event);
        break;
      case "customer.updated":
        handleCustomerUpdated(event);
        break;
      case "customer.suspended":
        handleCustomerSuspended(event);
        break;
      case "user.role_changed":
        handleUserRoleChanged(event);
        break;
      default:
        LOG.warn("Unknown event type: {}", eventType);
    }
  }

  private void handleCustomerCreated(Map<String, Object> event) throws Exception {
    Map<String, Object> customer = (Map<String, Object>) event.get("customer");
    String customerId = (String) customer.get("id");
    String tenantId = "customer-" + customerId;

    // Check if storage is entitled
    Map<String, Object> entitlements = (Map<String, Object>) customer.get("entitlements");
    Map<String, Object> storageEnt = (Map<String, Object>) entitlements.get("storage");

    if (!(Boolean) storageEnt.get("enabled")) {
      LOG.info("Storage not enabled for customer {}, skipping tenant creation", customerId);
      return;
    }

    // Check if already exists (idempotency)
    if (tenantService.tenantExists(tenantId)) {
      LOG.info("Tenant already exists: {}, skipping", tenantId);
      return;
    }

    // Create tenant
    long quotaGB = ((Number) storageEnt.get("quotaGB")).longValue();
    long quotaBytes = quotaGB * 1024 * 1024 * 1024;

    tenantService.createTenant(
      tenantId,
      (String) customer.get("admin_email"),
      quotaBytes
    );

    // Create Ranger roles for BSS roles
    List<Map<String, Object>> bssRoles = (List<Map<String, Object>>) customer.get("bss_roles");
    for (Map<String, Object> bssRole : bssRoles) {
      String roleName = (String) bssRole.get("name");
      List<String> users = (List<String>) bssRole.get("users");

      roleMapper.createRangerRoleForBSSRole(tenantId, roleName, users);
    }

    LOG.info("Tenant created from BSS event: {}", tenantId);
  }

  private void handleCustomerUpdated(Map<String, Object> event) {
    // Handle tier upgrades, quota changes, etc.
  }

  private void handleCustomerSuspended(Map<String, Object> event) {
    Map<String, Object> customer = (Map<String, Object>) event.get("customer");
    String tenantId = "customer-" + customer.get("id");

    try {
      tenantService.suspendTenant(tenantId);
      LOG.info("Tenant suspended from BSS event: {}", tenantId);
    } catch (Exception e) {
      LOG.error("Failed to suspend tenant: {}", tenantId, e);
    }
  }

  private void handleUserRoleChanged(Map<String, Object> event) {
    // Update Ranger role membership when BSS RBAC roles change
    String userId = (String) event.get("user_id");
    String customerId = (String) event.get("customer_id");
    List<String> newRoles = (List<String>) event.get("new_roles");

    roleMapper.syncUserRoles(customerId, userId, newRoles);
  }
}
```

**Configuration:**

```xml
<!-- ozone-site.xml -->
<property>
  <name>ozone.bss.kafka.brokers</name>
  <value>kafka1:9092,kafka2:9092,kafka3:9092</value>
  <description>Kafka broker addresses</description>
</property>

<property>
  <name>ozone.bss.kafka.topic</name>
  <value>bss.customer.lifecycle</value>
  <description>Kafka topic for BSS customer lifecycle events</description>
</property>

<property>
  <name>ozone.bss.event.consumer.enabled</name>
  <value>true</value>
  <description>Enable BSS event consumer in Admin Gateway</description>
</property>
```

**Pros:**
- ✅ Fully decoupled (no runtime dependency on BSS)
- ✅ Tenants created proactively (low latency on first request)
- ✅ Scales to many services (Storage, GPU, VM, Containers)
- ✅ Audit trail via message broker
- ✅ BSS RBAC roles synced automatically

**Cons:**
- ⚠️ Requires message broker infrastructure (Kafka/RabbitMQ)
- ⚠️ Eventual consistency (tenant creation lags 1-5 seconds)
- ⚠️ Need to handle event ordering, idempotency, retries

---

### Pattern C: Hybrid (Best Reliability)

Combine both patterns for best reliability:
- **Event-Stream** for proactive tenant provisioning
- **Pull-Based** as fallback if event consumer lags

```java
@Override
public void filter(ContainerRequestContext requestContext) {
  DecodedJWT jwt = validateJWT(requestContext);
  String customerId = jwt.getClaim("customer_id").asString();
  String tenantId = "customer-" + customerId;

  // Normal case: Tenant already created by event consumer
  if (!tenantService.tenantExists(tenantId)) {
    // Fallback: Event consumer might be lagging, pull from BSS
    LOG.warn("Tenant not found locally (event lag?), pulling from BSS: {}", tenantId);

    BSSClient.Customer customer = bssClient.getCustomer(customerId);

    if ("suspended".equals(customer.getStatus())) {
      throw new ForbiddenException("Customer account is suspended");
    }

    Map<String, Object> storageEnt = customer.getEntitlements().get("storage");
    if (!(Boolean) storageEnt.get("enabled")) {
      throw new ForbiddenException("Storage not enabled for this customer");
    }

    long quotaGB = ((Number) storageEnt.get("quotaGB")).longValue();
    tenantService.createTenant(tenantId, customer.getAdminEmail(), quotaGB * 1024 * 1024 * 1024);
  }

  TenantContext.setTenantId(tenantId);
}
```

**Pros:**
- ✅ Best reliability (events + pull fallback)
- ✅ Low latency (tenants proactively created)
- ✅ Fault tolerant (works even if Kafka is down briefly)

**Cons:**
- ⚠️ Higher complexity (both patterns implemented)
- ⚠️ Higher operational overhead

---

## Storage IAM Implementation (Ranger MVP)

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                  ADMIN GATEWAY (NEW)                             │
│                   Port: 9879                                      │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OAuth2Filter                                              │  │
│  │  - Validates JWT from BSS Keycloak                         │  │
│  │  - Extracts customer_id, bss_roles from claims             │  │
│  │  - Pulls customer metadata from BSS (pull-based)           │  │
│  │  - OR receives tenant via event consumer (event-based)     │  │
│  │  - Creates tenant on-demand if not exists                  │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Role Mapping Service                                      │  │
│  │  - Maps BSS roles → Ranger roles                           │  │
│  │  - Applies deny rules from bss-role-mapping.yaml           │  │
│  │  - Optionally queries BSS for dynamic deny policies        │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  REST Resources                                            │  │
│  │  - TenantResource: /api/v1/tenants (CRUD)                  │  │
│  │  - AccessKeyResource: /api/v1/tenants/{id}/access-keys     │  │
│  │  - MetricsResource: /api/v1/tenants/{id}/metrics (for BSS) │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────┬───────────────────────────────────────────┘
                       │ RPC
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                  OZONE MANAGER                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OMMultiTenantManager (Enhanced)                           │  │
│  │  - Tenant CRUD                                             │  │
│  │  - User assignment to tenants                              │  │
│  │  - Volume-per-tenant (s3v-{tenantId})                      │  │
│  │  - Access key generation (tenantId$userId format)          │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Authorization Layer                                       │  │
│  │  1. BSS Deny Check (optional, via BSSPolicyClient)         │  │
│  │  2. Ranger Policy Evaluation                               │  │
│  │  3. Bucket Policy Evaluation (AWS-compatible)              │  │
│  │  4. ACL Evaluation                                         │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────┬───────────────────────────────────────────┘
                       │ REST API (HTTPS + Kerberos/Basic)
                       ▼
           ┌─────────────────────┐
           │  Apache Ranger      │
           │  - Policies         │
           │  - Roles            │
           │  - Audit Logs       │
           └─────────────────────┘
```

### Ranger Policy Model with BSS RBAC Integration

```yaml
# Tenant: acme-corp
# Volume: s3v-acme-corp

Roles (created from BSS roles):
  - Name: acme-corp-CustomerAdminRole
    Users: [admin@acme.com]

  - Name: acme-corp-DeveloperRole
    Users: [alice@acme.com, bob@acme.com]

  - Name: acme-corp-ViewerRole
    Users: [charlie@acme.com]

  - Name: acme-corp-ComplianceOfficerRole
    Users: [compliance@acme.com]

Policies:
  - Name: acme-corp-VolumeAccess
    Resources:
      volume: [s3v-acme-corp]
    Roles:
      - acme-corp-CustomerAdminRole: [ALL]
      - acme-corp-DeveloperRole: [READ, LIST, READ_ACL]
      - acme-corp-ViewerRole: [READ, LIST]
      - acme-corp-ComplianceOfficerRole: [READ, LIST]
    Labels: [ozone-tenant-policy]

  - Name: acme-corp-BucketAccess
    Resources:
      volume: [s3v-acme-corp]
      bucket: [*]               # All buckets
    Roles:
      - acme-corp-CustomerAdminRole: [ALL]
      - acme-corp-DeveloperRole: [CREATE, READ, WRITE, DELETE]
      - acme-corp-ViewerRole: [READ, LIST]
    Deny Conditions:
      - For: acme-corp-DeveloperRole
        If: bucket matches "compliance*"
        Reason: "Compliance buckets restricted to ComplianceOfficer"

  - Name: acme-corp-ComplianceBucketAccess
    Resources:
      volume: [s3v-acme-corp]
      bucket: [compliance*]
      key: [/*]
    Roles:
      - acme-corp-CustomerAdminRole: [ALL]
      - acme-corp-ComplianceOfficerRole: [READ, LIST]
    Description: "Only CustomerAdmin and ComplianceOfficer can access compliance buckets"

  - Name: acme-corp-DataBucketAccess
    Resources:
      volume: [s3v-acme-corp]
      bucket: [data]
      key: [/*]
    Roles:
      - acme-corp-CustomerAdminRole: [ALL]
      - acme-corp-DeveloperRole: [READ, WRITE, DELETE]
      - acme-corp-ViewerRole: [READ, LIST]
```

### Role Mapper Implementation

```java
// hadoop-ozone/admin-gateway/.../RoleMapper.java
package org.apache.ozone.admin.auth;

import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLType;
import org.yaml.snakeyaml.Yaml;

import javax.inject.Singleton;
import java.io.InputStream;
import java.util.*;

@Singleton
public class RoleMapper {
  private static final Logger LOG = LoggerFactory.getLogger(RoleMapper.class);

  private Map<String, RoleMapping> roleMappings = new HashMap<>();

  public RoleMapper() {
    loadRoleMappings();
  }

  private void loadRoleMappings() {
    // Load bss-role-mapping.yaml
    Yaml yaml = new Yaml();
    InputStream input = getClass().getResourceAsStream("/bss-role-mapping.yaml");
    Map<String, Object> config = yaml.load(input);

    Map<String, Map<String, Object>> mappings =
        (Map<String, Map<String, Object>>) config.get("bss_role_mappings");

    for (Map.Entry<String, Map<String, Object>> entry : mappings.entrySet()) {
      String bssRole = entry.getKey();
      Map<String, Object> mapping = entry.getValue();

      RoleMapping roleMapping = new RoleMapping();
      roleMapping.setBssRole(bssRole);
      roleMapping.setOzoneRole((String) mapping.get("ozone_role"));
      roleMapping.setRangerRole((String) mapping.get("ranger_role"));
      roleMapping.setPermissions((List<String>) mapping.get("permissions"));
      roleMapping.setDenyRules((List<Map<String, String>>) mapping.get("deny_rules"));
      roleMapping.setAllowedPrefixes((List<String>) mapping.get("allowed_prefixes"));

      roleMappings.put(bssRole, roleMapping);
    }

    LOG.info("Loaded {} BSS role mappings", roleMappings.size());
  }

  public RoleMapping getMapping(String bssRole) {
    return roleMappings.get(bssRole);
  }

  public List<String> getMappedPermissions(String bssRole) {
    RoleMapping mapping = roleMappings.get(bssRole);
    return mapping != null ? mapping.getPermissions() : Collections.emptyList();
  }

  public String getRangerRole(String tenantId, String bssRole) {
    RoleMapping mapping = roleMappings.get(bssRole);
    if (mapping == null) {
      return null;
    }
    // Replace {tenantId} placeholder
    return mapping.getRangerRole().replace("{tenantId}", tenantId);
  }

  public boolean checkDenyRule(String bssRole, String resource) {
    RoleMapping mapping = roleMappings.get(bssRole);
    if (mapping == null || mapping.getDenyRules() == null) {
      return false;
    }

    for (Map<String, String> denyRule : mapping.getDenyRules()) {
      String resourcePattern = denyRule.get("resource");
      if (resourceMatches(resource, resourcePattern)) {
        LOG.debug("BSS deny rule matched: role={}, resource={}, pattern={}",
            bssRole, resource, resourcePattern);
        return true;
      }
    }

    return false;
  }

  private boolean resourceMatches(String resource, String pattern) {
    // Simple wildcard matching: */compliance/* matches foo/compliance/bar.txt
    String regex = pattern.replace("*", ".*");
    return resource.matches(regex);
  }

  public void createRangerRoleForBSSRole(String tenantId, String bssRole, List<String> users)
      throws IOException {
    String rangerRoleName = getRangerRole(tenantId, bssRole);
    if (rangerRoleName == null) {
      LOG.warn("No Ranger role mapping found for BSS role: {}", bssRole);
      return;
    }

    // Create Ranger role
    Role role = new Role.Builder()
        .setName(rangerRoleName)
        .addUsers(users)
        .setDescription("Auto-created from BSS role: " + bssRole)
        .build();

    rangerClient.createRole(role);

    // Create Ranger policies based on permissions
    List<String> permissions = getMappedPermissions(bssRole);
    createRangerPoliciesForRole(tenantId, rangerRoleName, permissions);

    LOG.info("Created Ranger role {} for BSS role {} with {} users",
        rangerRoleName, bssRole, users.size());
  }

  private void createRangerPoliciesForRole(String tenantId, String rangerRoleName,
      List<String> permissions) throws IOException {
    String volumeName = "s3v-" + tenantId;

    // Volume-level policy
    Policy volumePolicy = new Policy.Builder()
        .setName(tenantId + "-" + rangerRoleName + "-VolumeAccess")
        .addVolume(volumeName)
        .addRoleAcl(rangerRoleName, convertPermissionsToAcls(permissions))
        .build();

    rangerClient.createPolicy(volumePolicy);

    // Bucket-level policy
    Policy bucketPolicy = new Policy.Builder()
        .setName(tenantId + "-" + rangerRoleName + "-BucketAccess")
        .addVolume(volumeName)
        .addBucket("*")  // All buckets
        .addRoleAcl(rangerRoleName, convertPermissionsToAcls(permissions))
        .build();

    rangerClient.createPolicy(bucketPolicy);
  }

  private Collection<Acl> convertPermissionsToAcls(List<String> permissions) {
    List<Acl> acls = new ArrayList<>();
    for (String perm : permissions) {
      // Convert s3:GetObject → READ, s3:PutObject → WRITE, etc.
      ACLType aclType = convertS3PermissionToACLType(perm);
      if (aclType != null) {
        acls.add(Acl.allow(aclType));
      }
    }
    return acls;
  }

  public void syncUserRoles(String customerId, String userId, List<String> newBSSRoles) {
    // Update Ranger role memberships when BSS roles change
    String tenantId = "customer-" + customerId;

    for (String bssRole : newBSSRoles) {
      String rangerRoleName = getRangerRole(tenantId, bssRole);
      if (rangerRoleName != null) {
        try {
          Role role = rangerClient.getRole(rangerRoleName);
          if (!role.getUsers().contains(userId)) {
            role.addUser(userId);
            rangerClient.updateRole(role.getId(), role);
            LOG.info("Added user {} to Ranger role {}", userId, rangerRoleName);
          }
        } catch (IOException e) {
          LOG.error("Failed to sync user {} to role {}", userId, rangerRoleName, e);
        }
      }
    }
  }

  public static class RoleMapping {
    private String bssRole;
    private String ozoneRole;
    private String rangerRole;
    private List<String> permissions;
    private List<Map<String, String>> denyRules;
    private List<String> allowedPrefixes;

    // Getters/setters
    public String getBssRole() { return bssRole; }
    public void setBssRole(String bssRole) { this.bssRole = bssRole; }

    public String getOzoneRole() { return ozoneRole; }
    public void setOzoneRole(String ozoneRole) { this.ozoneRole = ozoneRole; }

    public String getRangerRole() { return rangerRole; }
    public void setRangerRole(String rangerRole) { this.rangerRole = rangerRole; }

    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }

    public List<Map<String, String>> getDenyRules() { return denyRules; }
    public void setDenyRules(List<Map<String, String>> denyRules) { this.denyRules = denyRules; }

    public List<String> getAllowedPrefixes() { return allowedPrefixes; }
    public void setAllowedPrefixes(List<String> allowedPrefixes) {
      this.allowedPrefixes = allowedPrefixes;
    }
  }
}
```

### Access Key Management

```java
// hadoop-ozone/admin-gateway/.../AccessKeyResource.java
package org.apache.ozone.admin.iam;

import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.om.helpers.S3SecretValue;
import org.apache.ozone.admin.model.AccessKeyDto;
import org.apache.ozone.admin.security.TenantContext;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

@Path("/api/v1/tenants/{tenantId}/access-keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccessKeyResource {
  private static final Logger LOG = LoggerFactory.getLogger(AccessKeyResource.class);

  @Inject
  private OzoneClient ozoneClient;

  /**
   * Generate S3 access key for a tenant user.
   * This is called by BSS portal when user requests S3 credentials.
   */
  @POST
  public Response createAccessKey(
      @PathParam("tenantId") String tenantId,
      @QueryParam("userId") String userId
  ) throws IOException {
    // Authorization: Only tenant admin or user themselves
    if (!TenantContext.canManageAccessKeys(tenantId, userId)) {
      return Response.status(Response.Status.FORBIDDEN).build();
    }

    ObjectStore objectStore = ozoneClient.getObjectStore();

    // Generate access ID: tenantId$userId
    String accessId = tenantId + "$" + userId;

    // Get secret from OM (creates if not exists)
    S3SecretValue secret = objectStore.getS3Secret(accessId);

    AccessKeyDto keyDto = AccessKeyDto.builder()
        .accessKeyId(accessId)
        .secretAccessKey(secret.getAwsSecret())  // Only returned on creation!
        .tenantId(tenantId)
        .userId(userId)
        .createdAt(System.currentTimeMillis())
        .build();

    LOG.info("Created access key for user {} in tenant {}", userId, tenantId);

    return Response.status(Response.Status.CREATED)
        .entity(keyDto)
        .build();
  }

  /**
   * Revoke (delete) an access key.
   */
  @DELETE
  @Path("/{accessId}")
  public Response revokeAccessKey(
      @PathParam("tenantId") String tenantId,
      @PathParam("accessId") String accessId
  ) throws IOException {
    if (!TenantContext.canManageAccessKeys(tenantId, null)) {
      return Response.status(Response.Status.FORBIDDEN).build();
    }

    ObjectStore objectStore = ozoneClient.getObjectStore();
    objectStore.revokeS3Secret(accessId);

    LOG.info("Revoked access key: {}", accessId);

    return Response.noContent().build();
  }

  /**
   * List access keys for tenant (without secrets).
   */
  @GET
  public Response listAccessKeys(@PathParam("tenantId") String tenantId) throws IOException {
    if (!TenantContext.canAccessTenant(tenantId)) {
      return Response.status(Response.Status.FORBIDDEN).build();
    }

    // List all access keys for tenant
    List<AccessKeyDto> keys = tenantService.listAccessKeys(tenantId);

    return Response.ok(keys).build();
  }
}
```

### Usage Metrics API for BSS Billing

```java
// hadoop-ozone/admin-gateway/.../MetricsResource.java
@Path("/api/v1/tenants/{tenantId}/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class MetricsResource {

  @Inject
  private OzoneClient ozoneClient;

  /**
   * Get storage usage metrics for BSS billing.
   */
  @GET
  public Response getMetrics(
      @PathParam("tenantId") String tenantId,
      @QueryParam("start") long startTime,
      @QueryParam("end") long endTime
  ) throws IOException {
    // Authorization: BSS service account or tenant admin
    if (!TenantContext.canViewMetrics(tenantId)) {
      return Response.status(Response.Status.FORBIDDEN).build();
    }

    ObjectStore objectStore = ozoneClient.getObjectStore();
    String volumeName = "s3v-" + tenantId;
    OzoneVolume volume = objectStore.getVolume(volumeName);

    // Aggregate metrics
    long totalBytes = volume.getUsedBytes();
    long quotaBytes = volume.getQuotaInBytes();

    // Get request counts from audit logs (simplified)
    Map<String, Long> requestCounts = getRequestCounts(tenantId, startTime, endTime);

    MetricsDto metrics = MetricsDto.builder()
        .tenantId(tenantId)
        .periodStart(startTime)
        .periodEnd(endTime)
        .totalBytes(totalBytes)
        .quotaBytes(quotaBytes)
        .utilizationPercent((totalBytes * 100.0) / quotaBytes)
        .requestCounts(requestCounts)
        .build();

    return Response.ok(metrics).build();
  }

  private Map<String, Long> getRequestCounts(String tenantId, long startTime, long endTime) {
    // Query audit logs for s3GetObject, s3PutObject, etc.
    // Simplified example:
    return Map.of(
        "s3GetObject", 15000L,
        "s3PutObject", 5000L,
        "s3DeleteObject", 500L,
        "s3ListBucket", 1000L
    );
  }
}
```

---

## SpiceDB Migration for Scale

### Why Migrate to SpiceDB?

**When to migrate:**
- Tenant count > 500 and growing to 1M+
- Authorization latency > 50ms at p99
- Ranger policy count > 10K
- Need for real-time permission checks at Google-scale

### SpiceDB Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                  SpiceDB Integration Architecture                │
└──────────────────────────────────────────────────────────────────┘

┌──────────────┐                              ┌─────────────────┐
│  S3 Gateway  │◄─────────────────────────────┤  Admin Gateway  │
│  (:9878)     │   Authorization Requests     │  (:9879)        │
└──────┬───────┘                              └────────┬────────┘
       │                                                │
       │ Check: can user:alice view object:doc.pdf?   │ Write tuples
       │                                                │
       ▼                                                ▼
┌──────────────────────────────────────────────────────────────┐
│              SpiceDB gRPC API (:50051)                       │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Permissions API                                       │  │
│  │  - CheckPermission(resource, permission, subject)      │  │
│  │  - LookupResources(subject, permission, resourceType)  │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Schema & Relationships API                            │  │
│  │  - WriteRelationships(tenant:acme#member@user:alice)   │  │
│  │  - DeleteRelationships(...)                            │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Consistency (ZedTokens)                               │  │
│  │  - Returns token after each write                      │  │
│  │  - Read-your-writes consistency                        │  │
│  └────────────────────────────────────────────────────────┘  │
└───────────────────┬──────────────────────────────────────────┘
                    │
                    ▼ Postgres/CockroachDB
       ┌─────────────────────────────────┐
       │  SpiceDB Datastore              │
       │  - Namespaces (schema)          │
       │  - Relationship tuples          │
       │  - Changelog (for caching)      │
       └─────────────────────────────────┘
```

### SpiceDB Schema Design

**Schema Definition (Zanzibar-inspired) with BSS RBAC Integration:**

```zed
// File: ozone-schema.zed

/**
 * Tenant represents an isolated multi-tenant namespace in Ozone.
 * Maps to BSS customer.
 */
definition tenant {
    relation member: user | user:*
    relation admin: user
    relation bss_customer_admin: user        // BSS CustomerAdmin role
    relation bss_developer: user             // BSS Developer role
    relation bss_viewer: user                // BSS Viewer role
    relation bss_compliance_officer: user    // BSS ComplianceOfficer role

    permission view = member + admin + bss_customer_admin + bss_developer + bss_viewer + bss_compliance_officer
    permission manage = admin + bss_customer_admin
    permission create_volume = admin + bss_customer_admin
    permission assign_user = admin + bss_customer_admin
}

/**
 * Volume is an S3-compatible namespace (s3v-{tenantId}).
 */
definition volume {
    relation tenant: tenant
    relation reader: user | user:*
    relation writer: user | user:*
    relation owner: user

    permission view = reader + writer + owner + tenant->view
    permission list_buckets = view
    permission create_bucket = writer + owner + tenant->manage
    permission delete = owner + tenant->manage
}

/**
 * Bucket contains objects (keys).
 */
definition bucket {
    relation volume: volume
    relation reader: user | user:* | role#member
    relation writer: user | user:* | role#member
    relation owner: user

    // Path-based caveats
    relation path_reader: user with path
    relation path_writer: user with path

    // BSS deny rules (compliance)
    relation compliance_restricted: user     // Blocks access to compliance buckets

    permission view = reader + writer + owner + volume->view
    permission list_objects = view
    permission read_object = (reader + owner + volume->view + path_reader) - compliance_restricted
    permission write_object = (writer + owner + volume->create_bucket + path_writer) - compliance_restricted
    permission delete_object = writer + owner - compliance_restricted
    permission read_acl = view
    permission write_acl = owner + volume->delete
}

/**
 * Object (S3 key).
 */
definition object {
    relation bucket: bucket
    relation reader: user | user:*
    relation writer: user | user:*
    relation owner: user

    // ABAC: Tagged-based access
    relation tagged_reader: user with tags

    permission view = reader + writer + owner + bucket->read_object
    permission read = view + tagged_reader
    permission write = writer + owner + bucket->write_object
    permission delete = owner + bucket->delete_object
}

/**
 * Role for RBAC.
 */
definition role {
    relation tenant: tenant
    relation member: user | user:*

    permission is_member = member
}

definition user {}
```

### Relationship Tuple Examples with BSS RBAC

**Scenario: Tenant `acme-123` with BSS roles**

```python
# 1. Create tenant and map BSS roles
tenant:acme-123#bss_customer_admin@user:admin@acme.com
tenant:acme-123#bss_developer@user:alice@acme.com
tenant:acme-123#bss_developer@user:bob@acme.com
tenant:acme-123#bss_viewer@user:charlie@acme.com
tenant:acme-123#bss_compliance_officer@user:compliance@acme.com

# 2. Create volume owned by tenant
volume:s3v-acme-123#tenant@tenant:acme-123
volume:s3v-acme-123#owner@user:admin@acme.com

# 3. Create "data" bucket
bucket:s3v-acme-123/data#volume@volume:s3v-acme-123
bucket:s3v-acme-123/data#owner@user:admin@acme.com
bucket:s3v-acme-123/data#writer@role:acme-123-DeveloperRole

# 4. Create "compliance" bucket with BSS deny rule
bucket:s3v-acme-123/compliance#volume@volume:s3v-acme-123
bucket:s3v-acme-123/compliance#owner@user:admin@acme.com
bucket:s3v-acme-123/compliance#reader@user:compliance@acme.com
# BSS deny rule: Developers cannot access compliance buckets
bucket:s3v-acme-123/compliance#compliance_restricted@user:alice@acme.com
bucket:s3v-acme-123/compliance#compliance_restricted@user:bob@acme.com

# 5. Role membership (maps BSS Developer role)
role:acme-123-DeveloperRole#tenant@tenant:acme-123
role:acme-123-DeveloperRole#member@user:alice@acme.com
role:acme-123-DeveloperRole#member@user:bob@acme.com

# 6. Object with permissions
object:s3v-acme-123/data/reports/q4.pdf#bucket@bucket:s3v-acme-123/data
object:s3v-acme-123/data/reports/q4.pdf#owner@user:alice@acme.com
object:s3v-acme-123/data/reports/q4.pdf#reader@user:bob@acme.com

# 7. Path-based caveat (logs bucket)
bucket:s3v-acme-123/logs#path_reader@user:bob@acme.com[path_prefix="/2024/"]
```

### SpiceDB Integration Code

```java
// hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/auth/SpiceDBAuthzService.java
package org.apache.hadoop.ozone.om.auth;

import com.authzed.api.v1.*;
import com.authzed.grpcutil.BearerToken;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * SpiceDB authorization service for Ozone with BSS RBAC integration.
 */
public class SpiceDBAuthzService {
  private static final Logger LOG = LoggerFactory.getLogger(SpiceDBAuthzService.class);

  private final PermissionsServiceGrpc.PermissionsServiceBlockingStub permissionsClient;
  private final ManagedChannel channel;

  public SpiceDBAuthzService(ConfigurationSource conf) throws IOException {
    String endpoint = conf.get("ozone.spicedb.endpoint", "localhost:50051");
    String presharedKey = conf.get("ozone.spicedb.preshared.key");

    this.channel = ManagedChannelBuilder.forTarget(endpoint)
        .usePlaintext()
        .build();

    BearerToken token = new BearerToken(presharedKey);
    this.permissionsClient = PermissionsServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(token);

    LOG.info("SpiceDB client initialized: {}", endpoint);
  }

  /**
   * Check if user has permission on resource.
   * Integrates with BSS RBAC deny rules.
   */
  public boolean checkPermission(
      String resourceType,
      String resourceId,
      String permission,
      String userId
  ) {
    CheckPermissionRequest request = CheckPermissionRequest.newBuilder()
        .setConsistency(Consistency.newBuilder()
            .setFullyConsistent(true)
            .build())
        .setResource(ObjectReference.newBuilder()
            .setObjectType(resourceType)
            .setObjectId(resourceId)
            .build())
        .setPermission(permission)
        .setSubject(SubjectReference.newBuilder()
            .setObject(ObjectReference.newBuilder()
                .setObjectType("user")
                .setObjectId(userId)
                .build())
            .build())
        .build();

    try {
      CheckPermissionResponse response = permissionsClient.checkPermission(request);

      boolean allowed = response.getPermissionship() ==
          CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION;

      LOG.debug("SpiceDB check: user={}, resource={}:{}, permission={}, result={}",
          userId, resourceType, resourceId, permission, allowed);

      return allowed;
    } catch (Exception e) {
      LOG.error("SpiceDB check failed, denying access", e);
      return false;
    }
  }

  /**
   * Write BSS role membership as SpiceDB relationship.
   */
  public void writeBSSRoleMembership(
      String tenantId,
      String bssRole,
      String userId
  ) throws IOException {
    // Map BSS role to SpiceDB relation
    String relation = mapBSSRoleToRelation(bssRole);
    if (relation == null) {
      LOG.warn("Unknown BSS role: {}", bssRole);
      return;
    }

    WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
        .addUpdates(RelationshipUpdate.newBuilder()
            .setOperation(RelationshipUpdate.Operation.OPERATION_CREATE)
            .setRelationship(Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                    .setObjectType("tenant")
                    .setObjectId(tenantId)
                    .build())
                .setRelation(relation)
                .setSubject(SubjectReference.newBuilder()
                    .setObject(ObjectReference.newBuilder()
                        .setObjectType("user")
                        .setObjectId(userId)
                        .build())
                    .build())
                .build())
            .build())
        .build();

    try {
      permissionsClient.writeRelationships(request);
      LOG.info("Wrote BSS role membership: tenant={}, role={}, user={}",
          tenantId, bssRole, userId);
    } catch (Exception e) {
      throw new IOException("Failed to write BSS role membership", e);
    }
  }

  /**
   * Write BSS compliance deny rule.
   */
  public void writeBSSDenyRule(
      String bucketId,
      String userId,
      String reason
  ) throws IOException {
    WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
        .addUpdates(RelationshipUpdate.newBuilder()
            .setOperation(RelationshipUpdate.Operation.OPERATION_CREATE)
            .setRelationship(Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                    .setObjectType("bucket")
                    .setObjectId(bucketId)
                    .build())
                .setRelation("compliance_restricted")
                .setSubject(SubjectReference.newBuilder()
                    .setObject(ObjectReference.newBuilder()
                        .setObjectType("user")
                        .setObjectId(userId)
                        .build())
                    .build())
                .build())
            .build())
        .build();

    try {
      permissionsClient.writeRelationships(request);
      LOG.info("Wrote BSS deny rule: bucket={}, user={}, reason={}",
          bucketId, userId, reason);
    } catch (Exception e) {
      throw new IOException("Failed to write BSS deny rule", e);
    }
  }

  private String mapBSSRoleToRelation(String bssRole) {
    switch (bssRole) {
      case "CustomerAdmin":
        return "bss_customer_admin";
      case "Developer":
        return "bss_developer";
      case "Viewer":
        return "bss_viewer";
      case "ComplianceOfficer":
        return "bss_compliance_officer";
      default:
        return null;
    }
  }

  /**
   * Write relationship tuple (generic).
   */
  public void writeRelationship(
      String resourceType,
      String resourceId,
      String relation,
      String subjectType,
      String subjectId
  ) throws IOException {
    WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
        .addUpdates(RelationshipUpdate.newBuilder()
            .setOperation(RelationshipUpdate.Operation.OPERATION_CREATE)
            .setRelationship(Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                    .setObjectType(resourceType)
                    .setObjectId(resourceId)
                    .build())
                .setRelation(relation)
                .setSubject(SubjectReference.newBuilder()
                    .setObject(ObjectReference.newBuilder()
                        .setObjectType(subjectType)
                        .setObjectId(subjectId)
                        .build())
                    .build())
                .build())
            .build())
        .build();

    try {
      permissionsClient.writeRelationships(request);
      LOG.info("Wrote relationship: {}:{}#{}@{}:{}",
          resourceType, resourceId, relation, subjectType, subjectId);
    } catch (Exception e) {
      throw new IOException("Failed to write relationship", e);
    }
  }
}
```

### Migration Strategy: Ranger → SpiceDB

#### Phase 1: Dual-Write (4 weeks)

```java
// hadoop-ozone/admin-gateway/.../TenantService.java (enhanced)
public TenantDto createTenant(String tenantId, String adminUser, long quota,
    String[] bssRoles) throws IOException {
  boolean rangerSuccess = false;
  boolean spicedbSuccess = false;

  try {
    // 1. Write to Ranger (existing)
    objectStore.createTenant(tenantId);
    rangerSuccess = true;

    // 2. Write to SpiceDB (new)
    writeToSpiceDB(tenantId, adminUser, bssRoles);
    spicedbSuccess = true;

    return getTenant(tenantId);
  } catch (Exception e) {
    // Rollback on failure
    if (rangerSuccess && !spicedbSuccess) {
      LOG.error("SpiceDB write failed, rolling back Ranger");
      objectStore.deleteTenant(tenantId);
    }
    throw new IOException("Tenant creation failed", e);
  }
}

private void writeToSpiceDB(String tenantId, String adminUser, String[] bssRoles)
    throws IOException {
  SpiceDBAuthzService spicedb = getSpiceDBService();

  // Write tenant
  spicedb.writeRelationship("tenant", tenantId, "admin", "user", adminUser);

  // Write BSS role memberships
  for (String bssRole : bssRoles) {
    spicedb.writeBSSRoleMembership(tenantId, bssRole, adminUser);
  }

  // Write volume
  String volumeName = "s3v-" + tenantId;
  spicedb.writeRelationship("volume", volumeName, "tenant", "tenant", tenantId);
  spicedb.writeRelationship("volume", volumeName, "owner", "user", adminUser);
}
```

#### Phase 2: Dual-Read with Comparison (2 weeks)

```java
public boolean checkAccess(String tenantId, String action, String resource, String userId) {
  boolean rangerResult = rangerAuthz.checkAccess(tenantId, action, resource, userId);
  boolean spicedbResult = spicedbAuthz.checkPermission(
      extractResourceType(resource),
      resource,
      mapActionToPermission(action),
      userId
  );

  if (rangerResult != spicedbResult) {
    LOG.warn("Authorization mismatch! Ranger={}, SpiceDB={}, resource={}",
        rangerResult, spicedbResult, resource);
    metrics.incrementMismatchCount();
  }

  return rangerResult;  // Trust Ranger during dual-read
}
```

#### Phase 3: Cutover to SpiceDB (1 week)

```properties
# Set feature flag
ozone.authz.spicedb.enabled=true
ozone.authz.ranger.enabled=false
```

#### Phase 4: Deprecate Ranger (1 week)

- Archive Ranger policies
- Remove `RangerClientMultiTenantAccessController`
- Update documentation

### Performance Comparison

| Metric | Ranger MVP | SpiceDB Target |
|--------|-----------|----------------|
| Authz Latency (p50) | 15ms | 3ms |
| Authz Latency (p99) | 50ms | 15ms |
| Throughput (per S3G) | 500 req/s | 5000 req/s |
| Error Rate | <0.1% | <0.01% |
| Tenant Capacity | 500 | 1M+ |
| BSS Role Sync Latency | 1-5s (batch) | <100ms (real-time) |

---

## AWS S3 Compatibility Layer

### AWS S3 Authorization Model

AWS S3 has **three layers** of authorization (evaluated in order):

```
1. IAM Policies (Identity-based)
   ├── User policies
   ├── Group policies
   └── Role policies

2. Bucket Policies (Resource-based)
   ├── Principal: Who
   ├── Action: What (s3:GetObject, s3:PutObject)
   ├── Resource: Where (arn:aws:s3:::bucket/*)
   └── Condition: When (IP, time, tags)

3. Bucket/Object ACLs (Legacy)
   ├── Canned ACLs: private, public-read
   ├── Grant-based: READ, WRITE, FULL_CONTROL
   └── Grantee: User, Group, AllUsers

Evaluation Logic:
- Explicit DENY always wins
- At least one ALLOW required
- Default: DENY
```

### Bucket Policy Model

```java
// hadoop-ozone/common/src/main/java/org/apache/hadoop/ozone/om/helpers/BucketPolicy.java
public class BucketPolicy {
  private String version = "2012-10-17";
  private String id;
  private List<Statement> statements;

  public static class Statement {
    private String sid;
    private Effect effect;  // Allow or Deny
    private Principal principal;
    private List<String> actions;  // ["s3:GetObject", "s3:PutObject"]
    private List<String> resources;  // ["arn:aws:s3:::bucket/*"]
    private Map<String, Map<String, Object>> conditions;

    public boolean matches(S3AccessRequest request) {
      // Check principal, action, resource, conditions
      if (!matchesPrincipal(request.getPrincipal())) {
        return false;
      }
      if (!matchesAction(request.getAction())) {
        return false;
      }
      if (!matchesResource(request.getResource())) {
        return false;
      }
      return matchesConditions(request);
    }
  }

  public AuthzDecision evaluate(S3AccessRequest request) {
    // 1. Check for explicit DENY
    for (Statement stmt : statements) {
      if (stmt.getEffect() == Effect.DENY && stmt.matches(request)) {
        return AuthzDecision.DENY;
      }
    }

    // 2. Check for ALLOW
    for (Statement stmt : statements) {
      if (stmt.getEffect() == Effect.ALLOW && stmt.matches(request)) {
        return AuthzDecision.ALLOW;
      }
    }

    // 3. Default: DENY
    return AuthzDecision.DENY;
  }
}
```

**Example Bucket Policy with BSS RBAC Integration:**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadGetObject",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::my-bucket/public/*"
    },
    {
      "Sid": "DenyNonCustomerAdminDelete",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:DeleteObject",
      "Resource": "arn:aws:s3:::my-bucket/production/*",
      "Condition": {
        "StringNotEquals": {
          "bss:role": "CustomerAdmin"
        }
      }
    },
    {
      "Sid": "DenyDeveloperAccessToCompliance",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": "arn:aws:s3:::my-bucket/compliance/*",
      "Condition": {
        "StringEquals": {
          "bss:role": "Developer"
        }
      }
    }
  ]
}
```

### Condition Key Evaluation

| Condition Key | Type | Example | Implementation |
|---------------|------|---------|----------------|
| `aws:SourceIp` | IP address | `192.168.1.0/24` | Extract from request |
| `aws:CurrentTime` | Timestamp | `2025-10-15T00:00:00Z` | System time |
| `aws:SecureTransport` | Boolean | `true` (HTTPS) | Check protocol |
| `s3:prefix` | String | `logs/2024/` | ListBucket prefix |
| `s3:ExistingObjectTag/<key>` | String | `classification=public` | Object tag check |
| `bss:role` | String | `CustomerAdmin` | BSS RBAC role from JWT |
| `bss:customer_id` | String | `acme-123` | Customer ID from JWT |

### Bucket Policy Evaluation

```java
// hadoop-ozone/ozone-manager/.../S3PolicyEvaluator.java
public class S3PolicyEvaluator {

  public boolean checkAccess(S3AccessRequest request, BucketPolicy bucketPolicy,
      String[] bssRoles) {
    // 1. Build request context with BSS roles
    request.addCondition("bss:role", bssRoles);
    request.addCondition("bss:customer_id", request.getTenantId());

    // 2. Evaluate bucket policy
    AuthzDecision policyDecision = bucketPolicy != null ?
        bucketPolicy.evaluate(request) : AuthzDecision.NOT_APPLICABLE;

    if (policyDecision == AuthzDecision.DENY) {
      LOG.debug("Bucket policy denied: {}", request);
      return false;  // Explicit deny
    }

    // 3. If policy allows, proceed to Ranger/SpiceDB
    if (policyDecision == AuthzDecision.ALLOW) {
      return true;
    }

    // 4. No policy or not applicable, check Ranger/SpiceDB
    return rangerAuthz.checkAccess(request);
  }
}
```

### S3 REST API Endpoints

```java
// hadoop-ozone/s3gateway/.../endpoint/BucketPolicyEndpoint.java
@Path("/{bucket}")
public class BucketPolicyEndpoint {

  @Inject
  private OMMetadataManager omMetadataManager;

  @PUT
  @Path("?policy")
  public Response putBucketPolicy(
      @PathParam("bucket") String bucketName,
      String policyJson
  ) {
    // Parse and validate policy
    BucketPolicy policy = parsePolicy(policyJson);
    validatePolicy(policy);  // Check for BSS role conditions

    // Store in OM
    omMetadataManager.storeBucketPolicy(bucketName, policy);

    LOG.info("Bucket policy updated: bucket={}", bucketName);
    return Response.noContent().build();
  }

  @GET
  @Path("?policy")
  public Response getBucketPolicy(@PathParam("bucket") String bucketName) {
    String policy = omMetadataManager.getBucketPolicy(bucketName);
    if (policy == null) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return Response.ok(policy).build();
  }

  @DELETE
  @Path("?policy")
  public Response deleteBucketPolicy(@PathParam("bucket") String bucketName) {
    omMetadataManager.deleteBucketPolicy(bucketName);
    return Response.noContent().build();
  }

  private void validatePolicy(BucketPolicy policy) throws InvalidPolicyException {
    // Validate BSS role conditions
    for (Statement stmt : policy.getStatements()) {
      if (stmt.getConditions() != null) {
        Map<String, Object> bssConditions = stmt.getConditions().get("bss:role");
        if (bssConditions != null) {
          // Ensure BSS roles are valid
          List<String> validRoles = Arrays.asList(
              "CustomerAdmin", "Developer", "Viewer", "ComplianceOfficer", "Finance"
          );
          // Validate...
        }
      }
    }
  }
}
```

---

## Security & Compliance

### Authentication Security

- ✅ **SigV4 validation** for S3 API (AWS SDK compatibility)
- ✅ **OAuth2/OIDC** for Admin Gateway (BSS Keycloak integration)
- ✅ **JWT signature verification** (BSS public key validation)
- ✅ **BSS RBAC claims** embedded in JWT tokens
- ⚠️ **Secret rotation** (implement 90-day policy)
- ⚠️ **MFA support** (defer to BSS IdP)
- ⚠️ **Rate limiting** (100 req/min/IP)

### Authorization Security

- ✅ **Least privilege** (default deny in Ranger/SpiceDB)
- ✅ **Explicit deny precedence** (BSS deny rules override)
- ✅ **Tenant isolation** (volume-per-tenant model)
- ✅ **RBAC via Ranger** with BSS role mapping
- ✅ **Dual-layer authorization** (BSS coarse-grained + Storage fine-grained)
- ⚠️ **ABAC** (tags, IP, time conditions in bucket policies)
- ⚠️ **Cross-tenant access prevention** (validate tenantId in every request)

### Audit Logging

- ✅ **Ranger audit logs** (policy evaluation decisions)
- ✅ **BSS role information** in audit logs
- ⚠️ **S3 access logs** (CloudTrail equivalent)
- ⚠️ **Admin activity logs** (tenant CRUD operations)
- ⚠️ **JSON format** with standard fields
- ⚠️ **90-day retention**
- ⚠️ **SIEM integration** (Splunk, ELK, etc.)

**Example Audit Log with BSS RBAC:**

```json
{
  "timestamp": "2025-10-15T10:30:00.123Z",
  "event_type": "S3_ACCESS",
  "principal": {
    "type": "user",
    "id": "alice@acme.com",
    "tenant": "acme-123",
    "bss_roles": ["Developer"],
    "customer_tier": "enterprise"
  },
  "action": "s3:PutObject",
  "resource": {
    "type": "object",
    "arn": "arn:aws:s3:::s3v-acme-123/data/q4.pdf"
  },
  "authorization": {
    "bss_layer": {
      "decision": "ALLOW",
      "deny_rule_checked": true,
      "latency_ms": 2
    },
    "storage_layer": {
      "decision": "ALLOW",
      "policy_id": "acme-123-data-write",
      "policy_type": "ranger",
      "latency_ms": 12
    }
  },
  "result": {
    "status_code": 200
  }
}
```

### Data Protection

- ✅ **Encryption at rest** (HDDS)
- ⚠️ **TLS for all traffic** (S3G, Admin Gateway, OM)
- ⚠️ **KMS integration** (key rotation)
- ⚠️ **PII masking** in logs

### Compliance (SOC2, GDPR, HIPAA)

| Requirement | Implementation |
|-------------|----------------|
| **Access Controls** | Dual-layer RBAC (BSS + Storage) + least privilege |
| **Audit Logs** | 90-day retention, immutable, includes BSS role context |
| **Data Encryption** | At-rest (HDDS) + in-transit (TLS) |
| **Right to be Forgotten** | Delete tenant data API |
| **Incident Response** | SIEM alerts on authorization anomalies |
| **Role-Based Segregation** | ComplianceOfficer role for restricted data |
| **Deny by Default** | All authorization denies unless explicitly allowed |

---

## Implementation Roadmap

### Timeline Overview

```
Week 1-3:   Ranger MVP + BSS Integration
Week 4-6:   AWS Compatibility Layer
Week 7-12:  SpiceDB Migration (optional, for scale)
Week 13-16: Advanced Features (ABAC, STS, Cross-tenant)
```

### Phase 1: Ranger MVP + BSS Integration (Weeks 1-3)

**Goal:** Working IAM system with BSS RBAC integration

**Deliverables:**
- [ ] OAuth2Filter with JWT validation
- [ ] BSSClient for pulling customer metadata (pull-based) OR BSSEventConsumer (event-stream)
- [ ] RoleMapper with bss-role-mapping.yaml
- [ ] TenantResource (CRUD)
- [ ] AccessKeyResource (S3 key generation)
- [ ] MetricsResource (for BSS billing)
- [ ] Ranger role creation from BSS roles
- [ ] BSS deny rules in role mapping
- [ ] Test with 5 test customers

**Estimated Effort:** 2-3 weeks (1-2 engineers)

---

### Phase 2: AWS S3 Compatibility (Weeks 4-6)

**Goal:** Bucket policies and ACLs with BSS condition keys

**Deliverables:**
- [ ] BucketPolicy model with BSS condition support
- [ ] S3PolicyEvaluator with BSS role evaluation
- [ ] BucketPolicyEndpoint (PUT/GET/DELETE ?policy)
- [ ] Condition key evaluation (bss:role, bss:customer_id, aws:SourceIp)
- [ ] Bucket ACL support (canned ACLs)
- [ ] Test with AWS SDK and bucket policies

**Estimated Effort:** 2-3 weeks (1-2 engineers)

---

### Phase 3: SpiceDB Migration (Weeks 7-12, Optional)

**Goal:** Migrate to SpiceDB for scale (1M+ tenants)

**Deliverables:**
- [ ] SpiceDB schema with BSS RBAC relations
- [ ] SpiceDBAuthzService
- [ ] Dual-write (Ranger + SpiceDB)
- [ ] Data migration tool (Ranger → SpiceDB)
- [ ] Dual-read comparison (detect drift)
- [ ] Cutover to SpiceDB
- [ ] Deprecate Ranger
- [ ] Performance testing (5000 req/s target)

**Estimated Effort:** 5-6 weeks (2-3 engineers)

---

### Phase 4: Advanced Features (Weeks 13-16, Optional)

**Goal:** Advanced IAM features

**Deliverables:**
- [ ] STS (AssumeRole) for temporary credentials
- [ ] Cross-tenant sharing (shared buckets)
- [ ] Secret rotation (90-day policy)
- [ ] SIEM integration (Splunk/ELK)
- [ ] Advanced ABAC (tag-based access)
- [ ] Audit log analytics dashboard for BSS

**Estimated Effort:** 3-4 weeks (1-2 engineers)

---

### Quick Start Implementation Steps

1. **Week 1: Setup**
   - Setup Keycloak for BSS OAuth2
   - Configure ozone-site.xml with BSS integration
   - Create bss-role-mapping.yaml
   - Build Admin Gateway module

2. **Week 2: Core Integration**
   - Implement OAuth2Filter
   - Implement BSSClient (or BSSEventConsumer)
   - Implement RoleMapper
   - Test JWT validation

3. **Week 3: APIs & Testing**
   - Implement TenantResource, AccessKeyResource, MetricsResource
   - Integrate with OM (tenant CRUD)
   - End-to-end test with 5 customers
   - Load testing (100 concurrent users)

---

### Configuration Summary

```xml
<!-- ozone-site.xml - Complete Configuration -->
<configuration>
  <!-- Multi-Tenancy -->
  <property>
    <name>ozone.om.multitenancy.enabled</name>
    <value>true</value>
  </property>

  <!-- BSS Integration -->
  <property>
    <name>ozone.bss.api.url</name>
    <value>https://bss.example.com/api/v1</value>
  </property>
  <property>
    <name>ozone.bss.service.token</name>
    <value>SERVICE_TOKEN_HERE</value>
  </property>
  <property>
    <name>ozone.bss.integration.enabled</name>
    <value>true</value>
  </property>
  <property>
    <name>ozone.bss.customer.cache.ttl</name>
    <value>900</value>
  </property>

  <!-- OAuth2 for Admin Gateway -->
  <property>
    <name>ozone.admin.gateway.oauth2.enabled</name>
    <value>true</value>
  </property>
  <property>
    <name>ozone.admin.gateway.oauth2.issuer</name>
    <value>https://bss-keycloak.example.com/realms/datacenter</value>
  </property>
  <property>
    <name>ozone.admin.gateway.oauth2.jwks.url</name>
    <value>https://bss-keycloak.example.com/realms/datacenter/protocol/openid-connect/certs</value>
  </property>
  <property>
    <name>ozone.admin.gateway.oauth2.audience</name>
    <value>ozone-storage</value>
  </property>

  <!-- Ranger Integration -->
  <property>
    <name>ozone.ranger.https.address</name>
    <value>https://ranger.example.com:6182</value>
  </property>
  <property>
    <name>ozone.ranger.service</name>
    <value>cm_ozone</value>
  </property>

  <!-- SpiceDB Integration (Future) -->
  <property>
    <name>ozone.spicedb.enabled</name>
    <value>false</value>
  </property>
  <property>
    <name>ozone.spicedb.endpoint</name>
    <value>spicedb.example.com:50051</value>
  </property>
  <property>
    <name>ozone.spicedb.preshared.key</name>
    <value>SPICEDB_KEY_HERE</value>
  </property>

  <!-- Event Stream (if using Pattern B) -->
  <property>
    <name>ozone.bss.kafka.brokers</name>
    <value>kafka1:9092,kafka2:9092</value>
  </property>
  <property>
    <name>ozone.bss.event.consumer.enabled</name>
    <value>false</value>
  </property>
</configuration>
```

---

## Problems to Discuss with BSS Team

This section provides a structured agenda for your cross-team alignment meeting with BSS. Use this to ensure the meeting is constructive and all critical integration points are addressed.

### Meeting Agenda Template

**Meeting Goal:** Align on IAM integration strategy between BSS and Storage teams

**Duration:** 90 minutes

**Attendees:**
- BSS Team: Product Owner, Tech Lead, Backend Engineer
- Storage Team: Product Owner, Tech Lead, Backend Engineer
- Optional: Security Lead, Compliance Officer

---

### Part 1: Architecture Alignment (30 minutes)

#### Question 1.1: BSS RBAC System Overview

**Context:** We understand BSS has its own RBAC system. We need to understand how it works to integrate properly.

**Questions for BSS:**
1. **What RBAC roles does BSS currently have?**
   - We've assumed: CustomerAdmin, Developer, Viewer, ComplianceOfficer, Finance
   - Are these accurate? Are there more roles?
   - Can you share the complete role list with descriptions?

2. **How are BSS roles assigned to users?**
   - Who can assign roles? (CustomerAdmin only? BSS admin?)
   - Can a user have multiple roles?
   - Can roles be assigned per-service (e.g., storage-only Developer)?

3. **What permissions do BSS roles have in BSS's system?**
   - What can a CustomerAdmin do in BSS portal?
   - What can a Developer do?
   - Do BSS roles control access to ALL services (storage, GPU, VM, containers) or just BSS portal features?

**Our Proposal:**
- Storage will **map** BSS roles to S3 permissions via configuration file (`bss-role-mapping.yaml`)
- Example: `Developer` BSS role → can `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` but NOT access `/compliance/*` buckets
- BSS doesn't need to understand S3-specific permissions (s3:GetObject vs s3:PutObject)
- Storage maintains full control over S3 authorization

**❓ Question:** Does this mapping approach work for BSS? Or does BSS need to manage S3 permissions directly?

---

#### Question 1.2: Multi-Service Integration Pattern

**Context:** Your datacenter has multiple services (Storage, GPU, VM, Containers). When BSS creates a customer, how should services be provisioned?

**Problem:**
- ❌ **Anti-pattern:** BSS calls each service's API directly (Storage, GPU, VM, Containers)
  - This creates tight coupling
  - If one service is down, customer creation fails
  - BSS needs to know about every service's API

**Questions for BSS:**
1. **Does BSS currently have a message broker (Kafka, RabbitMQ, NATS)?**
   - If yes, what topics/exchanges exist?
   - Can BSS publish customer lifecycle events?

2. **Is BSS willing to expose a read-only REST API for customer metadata?**
   - Example: `GET /api/v1/customers/{customerId}`
   - Returns: customer status, entitlements, quota, tier
   - Storage would call this on-demand (lazy provisioning)

3. **What's the expected customer onboarding volume?**
   - 10 customers/day? 1000 customers/day?
   - This helps us choose between pull-based (simple) vs event-stream (scalable)

**Our Proposal (Two Options):**

**Option A: Pull-Based (Recommended for Simplicity)**
- BSS creates customer in BSS database with entitlements for all services
- BSS issues OAuth2 JWT with `customer_id` claim
- Storage pulls customer metadata **on-demand** when first S3 request arrives
- Storage creates tenant lazily (Just-In-Time provisioning)
- GPU, VM, Container services do the same independently
- **Pros:** Simple, no message broker needed, works for < 5 services
- **Cons:** First request adds 50-200ms (mitigated by caching)

**Option B: Event-Stream (Recommended for Scale)**
- BSS publishes `customer.created` event to Kafka
- Each service subscribes independently
- Each service creates its own tenant when it receives the event
- **Pros:** Fully decoupled, proactive provisioning, scales to 10+ services
- **Cons:** Requires Kafka, eventual consistency (1-5s lag)

**❓ Question:** Which pattern does BSS prefer? Do you already have Kafka infrastructure?

---

#### Question 1.3: Service Entitlements

**Context:** A customer may not be entitled to all services. How are entitlements managed?

**Questions for BSS:**
1. **How are service entitlements determined?**
   - By customer tier (basic, premium, enterprise)?
   - By explicit configuration per customer?
   - Can entitlements change after customer creation (tier upgrade)?

2. **What should happen if a user tries to access a service they're not entitled to?**
   - Should Storage return 403 Forbidden?
   - Should BSS portal hide the service entirely?

3. **How are quotas/limits managed?**
   - Does BSS set storage quotas (e.g., 1TB, 10TB)?
   - Can customers request quota increases?
   - Who approves quota increases?

**Our Proposal:**
- BSS exposes entitlements in customer metadata API:
  ```json
  {
    "customer_id": "acme-123",
    "entitlements": {
      "storage": {"enabled": true, "quotaGB": 1000},
      "gpu": {"enabled": true, "gpuHours": 500},
      "vm": {"enabled": false},
      "container": {"enabled": true, "maxPods": 50}
    }
  }
  ```
- Storage checks `entitlements.storage.enabled` before creating tenant
- If not enabled, return 403 Forbidden

**❓ Question:** Does this entitlements model work for BSS?

---

### Part 2: API Contracts (30 minutes)

#### Question 2.1: BSS → Storage API Contract

**Context:** What APIs does BSS need from Storage?

**Our Proposal:**

**API 1: Generate S3 Access Key**
```yaml
POST /api/v1/tenants/{tenantId}/access-keys
Request:
  userId: string (e.g., alice@acme.com)
  expiryDays: number (optional)
Response:
  accessKeyId: string (e.g., "acme-123$alice")
  secretAccessKey: string (only returned once!)
  createdAt: timestamp
```
- **Who calls this:** BSS portal when user clicks "Generate S3 Credentials"
- **Authorization:** BSS service account token (M2M)

**API 2: Get Storage Usage Metrics**
```yaml
GET /api/v1/tenants/{tenantId}/metrics
Response:
  totalBytes: number
  quotaBytes: number
  requestCounts:
    s3GetObject: number
    s3PutObject: number
```
- **Who calls this:** BSS billing system (nightly batch)
- **Authorization:** BSS service account token (M2M)

**❓ Questions:**
1. Are these APIs sufficient for BSS needs?
2. Does BSS need any other Storage APIs (e.g., list buckets, delete tenant)?
3. How should BSS authenticate to Storage? (We propose M2M service account token)

---

#### Question 2.2: Storage → BSS API Contract

**Context:** What APIs does Storage need from BSS?

**Our Proposal (for Pull-Based Pattern):**

**API 1: Get Customer Metadata**
```yaml
GET /api/v1/customers/{customerId}
Response:
  customerId: string
  name: string
  tier: string (basic|premium|enterprise)
  status: string (active|suspended|deleted)
  adminEmail: string
  entitlements:
    storage:
      enabled: boolean
      quotaGB: number
    gpu:
      enabled: boolean
    # ... other services
  createdAt: timestamp
```
- **Who calls this:** Storage when first S3 request arrives
- **Authorization:** Storage service account token (M2M)
- **Caching:** Storage caches response for 15 minutes

**API 2 (Optional): Get User Details**
```yaml
GET /api/v1/users/{userId}
Response:
  userId: string
  email: string
  customerId: string
  bssRoles: [string]
  status: string (active|suspended)
```
- **Who calls this:** Storage for user validation (optional)

**API 3 (Optional): Get Customer Deny Policies**
```yaml
GET /api/v1/policies/customer/{customerId}
Response:
  denyRules: [
    {
      sid: string
      effect: "Deny"
      action: string
      resource: string
      condition: object
    }
  ]
```
- **Who calls this:** Storage if BSS wants to enforce dynamic deny rules

**❓ Questions:**
1. Can BSS expose these read-only APIs?
2. What authentication mechanism should Storage use? (We propose pre-shared service account token)
3. Are there rate limits we should be aware of?

---

#### Question 2.3: JWT Token Claims

**Context:** Storage will validate JWTs issued by BSS Keycloak. What claims should be included?

**Our Proposal:**
```json
{
  "iss": "https://bss-keycloak.example.com/realms/datacenter",
  "sub": "alice@acme.com",
  "aud": "ozone-storage",
  "exp": 1734260000,
  "customer_id": "acme-123",
  "bss_roles": ["Developer"],
  "entitlements": {
    "storage": true,
    "gpu": true,
    "vm": false
  }
}
```

**❓ Questions:**
1. Can BSS Keycloak include these custom claims?
2. Should entitlements be in JWT or fetched via API? (JWT is faster but less dynamic)
3. What's the JWT expiry time? (We recommend 1 hour for Admin API, 12 hours for portal)

---

### Part 3: Compliance & Security (20 minutes)

#### Question 3.1: BSS Deny Rules

**Context:** BSS may want to enforce compliance restrictions that override Storage permissions.

**Example Use Cases:**
- "Developers cannot access `/compliance/*` buckets" (compliance separation)
- "Suspended customers cannot access any service" (billing enforcement)
- "Only CustomerAdmin can delete objects in `/production/*`" (data protection)

**Questions for BSS:**
1. **Does BSS need to enforce these types of deny rules?**
   - If yes, should they be static (configured in Storage) or dynamic (queried from BSS)?

2. **How should Storage know about these rules?**
   - **Option A:** Static configuration in Storage's `bss-role-mapping.yaml`:
     ```yaml
     Developer:
       deny_rules:
         - resource: "*/compliance/*"
           reason: "Compliance data restricted to ComplianceOfficer role"
     ```
   - **Option B:** Dynamic API call to BSS:
     ```
     GET /api/v1/policies/customer/{customerId}
     ```

**Our Recommendation:**
- Start with **Option A (static)** for simplicity
- Add **Option B (dynamic)** later if needed for complex compliance rules

**❓ Question:** Which option does BSS prefer? Are there compliance requirements we should know about?

---

#### Question 3.2: Audit Logging

**Context:** Both teams need audit logs for compliance (SOC2, GDPR, HIPAA).

**Questions for BSS:**
1. **What audit events does BSS need from Storage?**
   - S3 API calls (GetObject, PutObject, DeleteObject)?
   - Tenant lifecycle events (created, suspended, deleted)?
   - Access key events (generated, revoked)?

2. **How should Storage provide these logs?**
   - **Option A:** Storage pushes to centralized logging (Splunk, ELK, DataDog)
   - **Option B:** Storage exposes logs via API (`GET /api/v1/tenants/{id}/audit-logs`)
   - **Option C:** Storage publishes to Kafka topic (`storage.audit.logs`)

3. **What retention policy?**
   - 90 days? 1 year? 7 years (compliance)?

**Our Proposal:**
- Storage logs all S3 API calls with BSS role context:
  ```json
  {
    "timestamp": "2025-10-15T10:30:00Z",
    "principal": {
      "user": "alice@acme.com",
      "tenant": "acme-123",
      "bss_roles": ["Developer"]
    },
    "action": "s3:PutObject",
    "resource": "arn:aws:s3:::bucket/file.txt",
    "result": "ALLOW"
  }
  ```
- Storage retains logs for 90 days
- BSS can query logs via API or pull from centralized logging

**❓ Question:** Which log delivery method does BSS prefer?

---

### Part 4: Testing & Rollout (10 minutes)

#### Question 4.1: Test Environment

**Questions for BSS:**
1. **Does BSS have a staging environment?**
   - Can we test integration with staging BSS before production?
   - Can we get test customer accounts in staging?

2. **How can Storage get test JWTs from BSS staging Keycloak?**
   - Can BSS provide test user accounts?
   - Can BSS provide service account token for Storage → BSS API calls?

**Our Proposal:**
- Week 1: Setup staging integration (BSS staging + Storage staging)
- Week 2: Test with 5 test customers
- Week 3: Load test with 100 concurrent users
- Week 4: Production rollout (10% → 50% → 100%)

**❓ Question:** Does this timeline work for BSS?

---

#### Question 4.2: Production Rollout

**Questions for BSS:**
1. **Should we do a phased rollout or big-bang?**
   - Phased: Start with 10% of customers, monitor, increase gradually
   - Big-bang: All customers at once

2. **What are the rollback criteria?**
   - If authorization error rate > 1%?
   - If first request latency > 500ms?

3. **Who should be on-call during rollout?**
   - BSS team needs to monitor OAuth2/JWT issuance
   - Storage team needs to monitor tenant provisioning and authorization

**Our Recommendation:**
- Phased rollout: 10% → 50% → 100% over 2 weeks
- Joint war room during rollout (BSS + Storage teams in same Slack channel)
- Rollback plan: Feature flag to disable BSS integration and fall back to manual tenant provisioning

**❓ Question:** Does BSS agree with this rollout strategy?

---

### Part 5: Open Questions & Action Items (10 minutes)

#### Open Questions

1. **Does BSS have any existing OAuth2/OIDC integrations we can learn from?**
   - How did GPU/VM services integrate with BSS?
   - Any lessons learned?

2. **What's the process for adding new BSS roles in the future?**
   - Example: If BSS adds a "DevOps" role, how should Storage know?
   - Do we need to update `bss-role-mapping.yaml`?

3. **How are customer tier upgrades handled?**
   - If a customer upgrades from basic (100GB) to enterprise (1TB), how is Storage notified?
   - Via event? Or Storage pulls on next request?

4. **What's the process for customer offboarding?**
   - Should Storage delete tenant data immediately or retain for 30 days?
   - Who approves data deletion?

---

### Action Items Template

**BSS Team:**
- [ ] Share complete list of BSS roles with descriptions
- [ ] Confirm if Kafka infrastructure exists (for event-stream pattern)
- [ ] Provide OpenAPI spec for BSS customer API (if exposing)
- [ ] Set up staging Keycloak for Storage testing
- [ ] Provide service account token for Storage → BSS API calls
- [ ] Review and approve `bss-role-mapping.yaml` configuration

**Storage Team:**
- [ ] Implement BSSClient for pull-based pattern OR BSSEventConsumer for event-stream
- [ ] Implement OAuth2Filter with JWT validation
- [ ] Implement RoleMapper with `bss-role-mapping.yaml`
- [ ] Create OpenAPI spec for Storage APIs (access key, metrics)
- [ ] Set up staging environment for BSS integration testing
- [ ] Provide service account endpoint for BSS → Storage API calls

**Joint:**
- [ ] Schedule follow-up meeting to review API contracts
- [ ] Set up shared Slack channel for integration discussions
- [ ] Schedule integration testing session (week of [DATE])
- [ ] Schedule production rollout planning meeting

---

### Decision Matrix

| Decision | Option A | Option B | Option C | Recommended | Reason |
|----------|----------|----------|----------|-------------|---------|
| **BSS Integration Pattern** | Pull-Based | Event-Stream | Hybrid | **Option A** (start), migrate to C if needed | Simplest to implement, no Kafka dependency |
| **BSS Role Management** | Static mapping | Dynamic API | JWT claims | **Option A** | Easiest to maintain, BSS doesn't manage S3 permissions |
| **BSS Deny Rules** | Static config | Dynamic API | Both | **Option A** (MVP), add B later | Start simple, add complexity if needed |
| **Audit Log Delivery** | API | Kafka | Central logging | **Option C** | Best for multi-team visibility |
| **Rollout Strategy** | Big-bang | Phased (10-50-100%) | Per-service | **Option B** | Safest, can rollback easily |

---

### Meeting Success Criteria

By the end of this meeting, we should have:
- ✅ Agreed on BSS integration pattern (pull-based vs event-stream)
- ✅ Confirmed BSS roles and mapping to Storage permissions
- ✅ Defined API contracts (BSS → Storage, Storage → BSS)
- ✅ Agreed on JWT claims format
- ✅ Identified who builds what (responsibility split)
- ✅ Set timeline for staging integration and testing
- ✅ Scheduled follow-up meetings

---

## Summary & Next Steps

### What We've Designed

1. **Dual-Layer Authorization**
   - BSS RBAC for coarse-grained access (CustomerAdmin, Developer, Viewer, ComplianceOfficer)
   - Storage authorization (Ranger/SpiceDB) for fine-grained S3 permissions

2. **Multi-Service Integration**
   - Pull-Based pattern (simple, works for < 5 services)
   - Event-Stream pattern (scalable, works for 10+ services)
   - Hybrid pattern (best reliability)

3. **Complete IAM System**
   - OAuth2/OIDC authentication with BSS Keycloak
   - SigV4 authentication for S3 API
   - Ranger MVP (2-3 weeks) → SpiceDB migration (for scale)
   - AWS S3 compatibility (bucket policies, ACLs, condition keys)
   - BSS role mapping and deny rules

### Critical Decisions to Make

1. **Which BSS integration pattern?**
   - Pull-Based (simple, no Kafka needed)
   - Event-Stream (requires Kafka, better for scale)
   - Hybrid (best reliability, higher complexity)

2. **When to migrate to SpiceDB?**
   - Start with Ranger MVP (good for 500 tenants)
   - Migrate when tenant count > 500 or authz latency > 50ms

3. **Which BSS APIs does BSS need to expose?**
   - Minimum: GET /customers/{id} (for pull-based)
   - Optional: GET /policies/customer/{id} (for dynamic deny rules)
   - Optional: Kafka events (for event-stream)

### Next Steps

1. **Align with BSS Team** (Week 1)
   - Walk through dual-layer authorization model
   - Agree on BSS integration pattern (pull-based recommended)
   - Define BSS API contract (GET /customers/{id} endpoint)
   - Confirm BSS roles (CustomerAdmin, Developer, Viewer, ComplianceOfficer, Finance)
   - Agree on JWT claims format

2. **Implement Ranger MVP** (Weeks 1-3)
   - Follow Phase 1 roadmap
   - Use BSSClient (pull-based) or BSSEventConsumer (event-stream)
   - Implement role mapping with deny rules
   - Test with 5 test customers

3. **Add AWS Compatibility** (Weeks 4-6, Optional)
   - Implement bucket policies with BSS condition keys
   - Test with AWS SDK

4. **Plan SpiceDB Migration** (Months 3-6, Optional)
   - Only if scale > 500 tenants or latency > 50ms
   - Follow 4-phase migration plan

---

**End of Consolidated Document**

**Document Statistics:**
- Total Sections: 8
- Total Lines: ~3500
- Code Examples: 15+
- Diagrams: 10+
- Decision Tables: 8+

**Key Files to Create:**
1. `hadoop-ozone/admin-gateway/src/main/java/org/apache/ozone/admin/bss/BSSClient.java`
2. `hadoop-ozone/admin-gateway/src/main/java/org/apache/ozone/admin/auth/OAuth2Filter.java`
3. `hadoop-ozone/admin-gateway/src/main/java/org/apache/ozone/admin/auth/RoleMapper.java`
4. `hadoop-ozone/admin-gateway/src/main/java/org/apache/ozone/admin/iam/AccessKeyResource.java`
5. `hadoop-ozone/admin-gateway/src/main/java/org/apache/ozone/admin/iam/MetricsResource.java`
6. `hadoop-ozone/admin-gateway/src/main/resources/bss-role-mapping.yaml`
7. `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/auth/SpiceDBAuthzService.java`

**Ready to implement? Start with Phase 1: Ranger MVP + BSS Integration!**

第一部分：架構對齊
  - BSS RBAC 系統概述（角色、權限、分配方式）
  - 多服務整合模式（拉取式 vs 事件串流式）
  - 服務權限管理（配額、Tier、權限控制）

第二部分：API Contract
- BSS → Storage API（產生存取金鑰、取得使用指標）
- Storage → BSS API（取得客戶資料、使用者詳細資料）
- JWT 令牌聲明（應包含哪些欄位）

第三部分：合規與安全
- 稽核日誌（格式、傳遞方式、保留期限）

第四部分：測試與部署
- 測試環境設定

---

第一部分：架構對齊

- 1.1 BSS RBAC 系統概述 - 了解角色清單、分配機制、權限範圍，目標是確認角色映射可行性
- 1.2 多服務整合模式 - 探討 Kafka 基礎設施、REST API
可行性、客戶導入量，目標是選定整合模式
- 1.3 服務權限 - 確認權限決定方式、未授權處理、配額管理，目標是定義 JSON 結構

第二部分：API 契約

- 2.1 BSS → Storage API - 產生存取金鑰、取得使用指標，目標是確認需求和驗證機制
- 2.2 Storage → BSS API - 取得客戶元資料、使用者詳細資料、拒絕策略，目標是確認 API
和快取策略
- 2.3 JWT 令牌聲明 - 確認必要欄位、內嵌或 API 取得、過期時間，目標是定義標準格式
