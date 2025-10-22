# BSS (Business Support System) Integration Addendum

**Version:** 2.0
**Date:** 2025-10-15
**Parent Document:** IAM_DESIGN.md
**Status:** Design Document - Multi-Team Integration

---

## ⚠️ IMPORTANT UPDATE (2025-10-15)

**This document has been updated to address multi-service datacenter requirements.**

Your datacenter provides **multiple services** (Storage/Ozone, GPU, VM, Containers), not just storage. The original webhook-based patterns in this document assumed BSS would call Storage APIs directly, which doesn't scale in multi-service environments.

**📖 For multi-service datacenter integration, see:**
- **[BSS_MULTISERVICE_ARCHITECTURE.md](./BSS_MULTISERVICE_ARCHITECTURE.md)** - **RECOMMENDED reading**
  - Architecture A: Pull-Based (recommended for simplicity)
  - Architecture B: Event-Stream (recommended for scale)
  - Hybrid approach (best reliability)

**This document (BSS_INTEGRATION_ADDENDUM.md) is kept for:**
- Team responsibility boundaries (still valid)
- Authorization delegation models (still valid)
- Role mapping concepts (still valid)
- Historical reference for webhook patterns (deprecated for multi-service)

**Quick Decision Guide:**
- **Simple setup, < 5 services?** → Use Pull-Based pattern (see BSS_MULTISERVICE_ARCHITECTURE.md)
- **Already have Kafka, 10+ services?** → Use Event-Stream pattern (see BSS_MULTISERVICE_ARCHITECTURE.md)
- **Need highest reliability?** → Use Hybrid pattern (see BSS_MULTISERVICE_ARCHITECTURE.md)

---

## Table of Contents

1. [Problem Statement](#problem-statement)
2. [Team Responsibilities & Boundaries](#team-responsibilities--boundaries)
3. [Integration Architectures](#integration-architectures)
4. [API Contracts & Interfaces](#api-contracts--interfaces)
5. [Authorization Delegation Models](#authorization-delegation-models)
6. [Implementation Patterns](#implementation-patterns)
7. [Migration & Rollout Strategy](#migration--rollout-strategy)
8. [Decision Framework](#decision-framework)

---

## Problem Statement

### Current Situation

Your organization has **two teams** managing different aspects of the datacenter:

1. **Storage Team (You):** Manages Apache Ozone, S3 Gateway, and storage-level authorization
2. **BSS Team:** Datacenter frontend managing **multiple services** (Storage, GPU, VM, Containers, etc.)

**IMPORTANT:** Your datacenter is **multi-service**, not just storage. When a customer signs up:
- BSS manages customer onboarding **once** for all services
- BSS does NOT call individual service "create tenant" APIs
- Each service (Storage, GPU, VM) provisions resources independently

### Key Concerns

1. **Unclear Boundaries:** Who manages what authentication/authorization data?
2. **RBAC Overlap:** BSS team wants their own RBAC, but Ozone already has Ranger/SpiceDB
3. **S3 Permission Depth:** How deep into S3 permissions does BSS need to go?
4. **Duplication Risk:** Both teams might end up managing similar user/role data
5. **Integration Complexity:** How to integrate BSS's RBAC with Ozone's authorization?
6. **Multi-Service Coordination:** BSS provisions Storage + GPU + VM + Containers - how should this work?

**⚠️ See [BSS_MULTISERVICE_ARCHITECTURE.md](./BSS_MULTISERVICE_ARCHITECTURE.md) for revised integration patterns that properly handle multi-service datacenters.**

---

## Team Responsibilities & Boundaries

### Recommended Responsibility Split

We recommend a **layered architecture** where each team owns specific concerns:

```
┌────────────────────────────────────────────────────────────────┐
│                    RESPONSIBILITY LAYERS                        │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  BSS TEAM (Business Support System)                            │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  1. Customer Management                                  │  │
│  │     - Customer accounts (billing entity)                 │  │
│  │     - Organizational hierarchy                           │  │
│  │     - Commercial contracts & pricing                     │  │
│  │                                                          │  │
│  │  2. Coarse-Grained Access Control                        │  │
│  │     - Which customers can access storage?                │  │
│  │     - Service tier entitlements (basic/premium/enterprise)│ │
│  │     - Quota allocation per customer                      │  │
│  │     - Enable/disable storage access                      │  │
│  │                                                          │  │
│  │  3. User Identity Management (High-Level)                │  │
│  │     - User accounts (tied to customers)                  │  │
│  │     - User roles: CustomerAdmin, Developer, Viewer      │  │
│  │     - SSO/SAML integration                              │  │
│  │     - MFA enforcement                                    │  │
│  │                                                          │  │
│  │  4. Self-Service Portal                                  │  │
│  │     - Dashboard (usage, quotas, billing)                 │  │
│  │     - User invitations                                   │  │
│  │     - API key generation (delegated to Storage)          │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
                            ▼ Delegates to
┌────────────────────────────────────────────────────────────────┐
│  STORAGE TEAM (Ozone/S3)                                       │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  1. Fine-Grained S3 Authorization                        │  │
│  │     - Bucket-level permissions (s3:CreateBucket)         │  │
│  │     - Object-level permissions (s3:GetObject, PutObject) │  │
│  │     - Prefix/path-based access (/logs/2024/*)            │  │
│  │     - Bucket policies & ACLs                             │  │
│  │                                                          │  │
│  │  2. Tenant Isolation & Multi-Tenancy                     │  │
│  │     - Volume-per-tenant mapping                          │  │
│  │     - Cross-tenant access prevention                     │  │
│  │     - Resource quotas (storage-level)                    │  │
│  │                                                          │  │
│  │  3. S3 Access Key Management                             │  │
│  │     - Access key generation (tenantId$userId)            │  │
│  │     - Secret key rotation                                │  │
│  │     - SigV4 signature validation                         │  │
│  │                                                          │  │
│  │  4. Storage-Specific Policies                            │  │
│  │     - Ranger/SpiceDB policy management                   │  │
│  │     - Conditional access (IP, time, tags)                │  │
│  │     - Audit logging (S3 API calls)                       │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

---

### Clear Boundaries Table

| Concern | BSS Team Owns | Storage Team Owns | Shared (Coordinated) |
|---------|---------------|-------------------|----------------------|
| **Customer Accounts** | ✅ Create, manage, bill | | BSS → Storage sync |
| **User Identity (SSO)** | ✅ User directory, LDAP/AD | | BSS IdP → Storage validates JWT |
| **High-Level Roles** | ✅ CustomerAdmin, Developer, Viewer | | Role mapping to storage permissions |
| **Tenant/Namespace** | Create request | ✅ Volume creation, lifecycle | BSS requests, Storage executes |
| **Quotas** | Set commercial limits | ✅ Enforce storage quotas | BSS sets, Storage enforces |
| **S3 Access Keys** | Request generation | ✅ Generate & manage keys | BSS triggers, Storage creates |
| **Bucket Permissions** | | ✅ s3:CreateBucket, DeleteBucket | |
| **Object Permissions** | | ✅ s3:GetObject, PutObject, DeleteObject | |
| **Bucket Policies** | | ✅ Policy CRUD & evaluation | |
| **Bucket ACLs** | | ✅ ACL management | |
| **Path-Based Access** | | ✅ /logs/*, /data/* permissions | |
| **SigV4 Auth** | | ✅ Signature validation | |
| **S3 Audit Logs** | View aggregated metrics | ✅ Capture & store logs | Storage logs → BSS analytics |
| **Billing/Metering** | ✅ Aggregate usage, invoicing | Emit usage metrics | Storage metrics → BSS billing |

---

## Integration Architectures

**⚠️ IMPORTANT UPDATE:** The architectures below assume BSS directly calls Storage APIs via webhooks. This is **NOT recommended** for multi-service datacenters.

**For multi-service datacenters (Storage + GPU + VM + Containers), please see:**
- **[BSS_MULTISERVICE_ARCHITECTURE.md](./BSS_MULTISERVICE_ARCHITECTURE.md)** - Recommended pull-based and event-stream patterns

The patterns below are kept for reference but should be adapted based on the multi-service guidance.

---

### Architecture 1: **BSS as Identity Provider (Legacy - See Multi-Service Doc)**

**Best for:** BSS wants to manage users/roles but NOT deep S3 permissions.

**⚠️ Note:** This shows webhook-based integration. For multi-service datacenters, use pull-based or event-stream patterns instead.

```
┌─────────────────────────────────────────────────────────────┐
│                      BSS System                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  BSS Database                                        │   │
│  │  - Customers (id, name, tier)                        │   │
│  │  - Users (id, email, customerId)                     │   │
│  │  - BSS Roles:                                        │   │
│  │    * CustomerAdmin (can manage all in customer)      │   │
│  │    * Developer (can use storage)                     │   │
│  │    * Viewer (read-only access)                       │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  BSS REST API                                        │   │
│  │  POST /customers                                     │   │
│  │  POST /customers/{id}/users                          │   │
│  │  POST /customers/{id}/storage-tenants (triggers)     │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  OAuth2/OIDC Provider (Keycloak)                     │   │
│  │  - Issues JWTs with claims:                          │   │
│  │    * sub: user ID                                    │   │
│  │    * customer_id: customer123                        │   │
│  │    * bss_roles: ["CustomerAdmin"]                    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  │ 1. User logs in → JWT
                  │ 2. Webhook: Customer created → Tenant
                  │ 3. API call: Generate S3 key
                  ▼
┌─────────────────────────────────────────────────────────────┐
│               Ozone Admin Gateway                           │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  OAuth2Filter (validates BSS JWT)                    │   │
│  │  - Checks JWT signature (BSS's public key)           │   │
│  │  - Extracts customer_id, bss_roles                   │   │
│  │  - Maps to Ozone tenant (customer_id → tenantId)     │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Role Mapping Service                                │   │
│  │  BSS Role → Ozone Permissions:                       │   │
│  │  - CustomerAdmin → Tenant Admin (full S3 access)     │   │
│  │  - Developer → Tenant User (read/write buckets)      │   │
│  │  - Viewer → Read-only (s3:GetObject, ListBucket)     │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Tenant Lifecycle API                                │   │
│  │  POST /api/v1/tenants                                │   │
│  │    (called by BSS webhook when customer created)     │   │
│  │  POST /api/v1/tenants/{id}/users                     │   │
│  │    (assign user to tenant)                           │   │
│  │  POST /api/v1/tenants/{id}/access-keys               │   │
│  │    (generate S3 credentials)                         │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼ RPC
┌─────────────────────────────────────────────────────────────┐
│               Ozone Manager                                 │
│  - Ranger/SpiceDB: Fine-grained S3 permissions              │
│  - Bucket policies, ACLs, path-based access                 │
│  - Audit logs (per-object access)                           │
└─────────────────────────────────────────────────────────────┘
```

**Pros:**
- Clear separation: BSS manages users/customers, Storage manages S3 permissions
- BSS doesn't need to understand S3 semantics
- Storage team retains full control over authorization logic
- Standard OAuth2 integration (well-understood)

**Cons:**
- Role mapping complexity (BSS roles → Ozone permissions)
- Webhook reliability (BSS must notify Storage of customer lifecycle)

**Data Flow Example (Legacy - Webhook-Based):**

**⚠️ This flow assumes BSS calls Storage APIs directly. For multi-service datacenters, see BSS_MULTISERVICE_ARCHITECTURE.md for pull-based or event-stream flows.**

```
1. BSS: Customer "Acme Corp" signs up
   → BSS DB: customer_id=acme-123, tier=enterprise
   → BSS provisions: Storage + GPU + VM + Containers (how?)

2. DEPRECATED: BSS calls Storage's webhook
   POST /api/v1/tenants {...}
   ❌ Problem: BSS would need to call GPU, VM, Container APIs too!

INSTEAD, use one of these patterns from BSS_MULTISERVICE_ARCHITECTURE.md:

Pattern A (Pull-Based):
2a. BSS: Issues JWT with customer_id claim
3a. User: First S3 request → Storage validates JWT
4a. Storage: Pulls customer data from BSS GET /customers/acme-123
5a. Storage: Creates tenant on-demand (lazy provisioning)

Pattern B (Event-Stream):
2b. BSS: Publishes customer.created event to Kafka
3b. Storage: Consumes event from Kafka
4b. Storage: Creates tenant if storage is entitled
5b. (GPU, VM, Container services do the same independently)

Continue with common flow:

6. BSS: User "alice@acme.com" invited by Customer Admin
   → BSS DB: user_id=alice, customer_id=acme-123, bss_role=Developer

7. Storage: Maps "Developer" → Ranger role "acme-123-UserRole"
   → Grants: s3:GetObject, PutObject, DeleteObject on acme-123 buckets

8. User: Generates S3 access key via BSS portal
   → BSS calls Storage API: POST /api/v1/tenants/acme-123/access-keys
   → Returns: {accessKeyId: "acme-123$alice", secretAccessKey: "..."}

9. User: Uses S3 SDK with access key
   aws s3 cp file.txt s3://my-bucket/
   → Ozone validates SigV4 signature
   → Ranger checks: alice has s3:PutObject on acme-123/my-bucket?
   → Success!
```

## API Contracts & Interfaces

**⚠️ UPDATED FOR MULTI-SERVICE:** These APIs have been revised for multi-service datacenter integration.

### Storage → BSS Team API Contract (Pull-Based Pattern)

**Storage needs to pull customer metadata from BSS:**

#### **1. Customer Metadata API (READ-ONLY)**

```yaml
# GET /api/v1/customers/{customerId}
# Storage pulls customer details when first S3 request arrives

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
      gpuHours: number
    vm:
      enabled: boolean
    container:
      enabled: boolean
      maxPods: number
  createdAt: timestamp
  metadata:
    billingAccountId: string
```

---

### BSS → Storage Team API Contract (Deprecated Webhook Pattern)

**⚠️ DEPRECATED for multi-service datacenters.** These APIs assume BSS calls Storage directly.

**For multi-service, use:**
- Pull-based: Storage calls BSS's GET /customers/{id} API
- Event-based: BSS publishes events, Storage consumes them

#### **1. Tenant Lifecycle API (DEPRECATED - Use Pull or Event Pattern)**

```yaml
# POST /api/v1/tenants
# ❌ DEPRECATED: Don't use in multi-service datacenters
# Instead: Storage auto-creates tenant on first user request (pull-based)
#     OR: Storage consumes customer.created event (event-based)

Request:
  tenantId: string (unique, e.g., "customer-acme-123")
  adminUser: string (email of initial admin)
  quota: number (bytes, from BSS commercial tier)
  metadata:
    customerId: string (BSS's customer ID)
    tier: string (basic|premium|enterprise)
    billingAccountId: string

Response:
  tenantId: string
  volumeName: string (e.g., "s3v-customer-acme-123")
  createdAt: timestamp
  status: string (active|pending|suspended)

# PUT /api/v1/tenants/{tenantId}/quota
# Update quota (when BSS changes customer tier)
# ⚠️ In pull-based pattern: Storage pulls latest quota on each request (cached)
# ⚠️ In event-based pattern: BSS publishes customer.updated event

Request:
  quotaInBytes: number

# DELETE /api/v1/tenants/{tenantId}
# Soft-delete tenant (when BSS customer churns)
# ⚠️ In pull-based: Storage checks customer.status == "deleted" and blocks access
# ⚠️ In event-based: BSS publishes customer.deleted event

Response:
  status: deleted
  dataRetentionDays: 30
```

#### **2. User Assignment API**

```yaml
# POST /api/v1/tenants/{tenantId}/users
# Assign user to tenant (called by BSS when user invited)

Request:
  userId: string (email or unique ID)
  bssRole: string (CustomerAdmin|Developer|Viewer)
  metadata:
    name: string
    email: string

Response:
  userId: string
  tenantId: string
  ozonePrincipal: string (e.g., "alice@EXAMPLE.COM")
  mappedRole: string (e.g., "acme-123-UserRole")

# DELETE /api/v1/tenants/{tenantId}/users/{userId}
# Remove user from tenant
```

#### **3. Access Key API**

```yaml
# POST /api/v1/tenants/{tenantId}/access-keys
# Generate S3 access key (user requests from BSS portal)

Request:
  userId: string
  expiryDays: number (optional, default: never)

Response:
  accessKeyId: string (e.g., "acme-123$alice")
  secretAccessKey: string (only returned once!)
  createdAt: timestamp
  expiresAt: timestamp (optional)

# DELETE /api/v1/tenants/{tenantId}/access-keys/{accessKeyId}
# Revoke access key
```

#### **4. Usage Metrics API**

```yaml
# GET /api/v1/tenants/{tenantId}/metrics
# Get storage usage metrics (for BSS billing)

Response:
  tenantId: string
  period:
    start: timestamp
    end: timestamp
  metrics:
    totalBytes: number (current storage usage)
    objectCount: number
    requestCount:
      s3GetObject: number
      s3PutObject: number
      s3DeleteObject: number
    bandwidth:
      egressBytes: number
      ingressBytes: number
```

---

### Additional BSS APIs for Multi-Service Integration

#### **1. User Identity Validation**

```yaml
# GET /api/v1/users/{userId}
# Storage validates user exists and gets details

Response:
  userId: string
  email: string
  customerId: string
  bssRoles: [string]
  status: string (active|suspended|deleted)
  entitlements:
    storage: boolean
    gpu: boolean
    vm: boolean
```

#### **2. Policy Retrieval (Optional, for Architecture 2 - Dual-Layer)**

```yaml
# GET /api/v1/policies/customer/{customerId}
# Get BSS high-level deny policies (if using dual-layer authz)

Response:
  customerId: string
  version: number
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

---

### Storage APIs for BSS (Unchanged)

**These APIs are still valid - BSS calls Storage for specific operations:**

#### **1. Access Key Generation**

```yaml
# POST /api/v1/tenants/{tenantId}/access-keys
# BSS portal requests S3 access key for user

Request:
  userId: string
  expiryDays: number (optional)

Response:
  accessKeyId: string (e.g., "acme-123$alice")
  secretAccessKey: string (only returned once!)
  createdAt: timestamp
  expiresAt: timestamp (optional)
```

#### **2. Usage Metrics**

```yaml
# GET /api/v1/tenants/{tenantId}/metrics
# BSS pulls storage usage for billing

Response:
  tenantId: string
  period:
    start: timestamp
    end: timestamp
  metrics:
    totalBytes: number
    objectCount: number
    requestCount:
      s3GetObject: number
      s3PutObject: number
    bandwidth:
      egressBytes: number
      ingressBytes: number
```

---

## Authorization Delegation Models

### Model 1: **JWT Claims-Based (Simplest)**

BSS issues JWT with embedded permissions:

```json
{
  "iss": "https://bss.example.com",
  "sub": "alice@acme.com",
  "aud": "ozone-admin-api",
  "customer_id": "acme-123",
  "bss_roles": ["CustomerAdmin"],
  "storage_permissions": {
    "tenant": "acme-123",
    "role": "admin",
    "quotaGB": 1000
  }
}
```

**Storage validates JWT and extracts `storage_permissions.role`.**

**Pros:** No extra API calls, fast
**Cons:** Permissions are static (cached in JWT until expiry)

---

### Model 2: **Role Mapping Table**

Storage maintains a mapping of BSS roles → Ozone permissions:

```java
// Role mapping configuration
public class BSSRoleMapper {
  private static final Map<String, List<String>> ROLE_MAPPING = Map.of(
    "CustomerAdmin", List.of("s3:*", "admin:*"),
    "Developer", List.of("s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"),
    "Viewer", List.of("s3:GetObject", "s3:ListBucket")
  );

  public List<String> mapToOzonePermissions(String bssRole) {
    return ROLE_MAPPING.getOrDefault(bssRole, List.of());
  }
}
```

**Storage applies mapping during authorization check.**

**Pros:** Flexible, can evolve independently
**Cons:** Requires coordination when adding new BSS roles

---

### Model 3: **Federated Authorization (Complex)**

BSS and Storage both check authorization (dual-layer):

```java
public boolean checkAccess(String userId, String action, String resource) {
  // Layer 1: BSS coarse-grained check (deny overrides)
  if (!bssClient.checkCustomerAccess(userId, extractCustomer(resource))) {
    return false;
  }

  // Layer 2: Storage fine-grained check (Ranger/SpiceDB)
  return rangerAuthz.checkAccess(userId, action, resource);
}
```

**Pros:** Defense-in-depth, compliance separation
**Cons:** Performance, complexity

---

## Implementation Patterns

**⚠️ UPDATED:** These patterns have been revised for multi-service datacenter integration.

**For complete implementation details, see [BSS_MULTISERVICE_ARCHITECTURE.md](./BSS_MULTISERVICE_ARCHITECTURE.md).**

### Pattern 1: **Pull-Based (Recommended for Multi-Service)**

**Storage pulls customer metadata from BSS on-demand:**

```java
// hadoop-ozone/admin-gateway/.../OAuth2Filter.java
@Override
public void filter(ContainerRequestContext requestContext) {
  DecodedJWT jwt = validateJWT(requestContext);
  String customerId = jwt.getClaim("customer_id").asString();
  String tenantId = "customer-" + customerId;

  // Check if tenant exists locally
  if (!tenantService.tenantExists(tenantId)) {
    LOG.info("Tenant not found, pulling from BSS: {}", tenantId);

    // Pull customer metadata from BSS
    Customer customer = bssClient.getCustomer(customerId);  // GET /customers/{id}

    // Check if storage is entitled
    if (!customer.getEntitlements().get("storage").get("enabled")) {
      throw new ForbiddenException("Storage not enabled for this customer");
    }

    // Create tenant on-demand
    long quotaGB = customer.getEntitlements().get("storage").get("quotaGB");
    tenantService.createTenant(tenantId, customer.getAdminEmail(), quotaGB * 1024 * 1024 * 1024);
  }

  TenantContext.setTenantId(tenantId);
}
```

**Pros:**
- ✅ No webhooks needed
- ✅ Works for multi-service (GPU, VM, Containers all do the same)
- ✅ Single source of truth (BSS)

**Cons:**
- ⚠️ First request latency (50-200ms)
- ⚠️ BSS must be available (mitigated by caching)

---

### Pattern 2: **Event Stream (Recommended for Scale)**

**BSS publishes events, Storage consumes:**

See full implementation in [BSS_MULTISERVICE_ARCHITECTURE.md](./BSS_MULTISERVICE_ARCHITECTURE.md#architecture-b-event-stream-with-service-owned-state).

```java
// Kafka consumer
private void handleCustomerCreated(Map<String, Object> event) {
  Map<String, Object> customer = event.get("customer");
  String customerId = customer.get("id");

  // Check if storage is entitled
  Map<String, Object> entitlements = customer.get("entitlements");
  if (!entitlements.get("storage").get("enabled")) {
    return;  // Skip, storage not entitled
  }

  // Create tenant
  tenantService.createTenant("customer-" + customerId, ...);
}
```

**Pros:**
- ✅ Fully decoupled
- ✅ Proactive provisioning
- ✅ Scales to many services

**Cons:**
- ⚠️ Requires message broker (Kafka/RabbitMQ)
- ⚠️ Eventual consistency

---

### Pattern 3: **Hybrid (Best Reliability)**

Combine event-stream for proactive provisioning + pull-based as fallback:

```java
@Override
public void filter(ContainerRequestContext requestContext) {
  String tenantId = extractTenantId(requestContext);

  // Normal case: Tenant already created by event consumer
  if (!tenantService.tenantExists(tenantId)) {
    // Fallback: Event consumer lagging, pull from BSS
    LOG.warn("Tenant not found (event lag?), pulling from BSS: {}", tenantId);
    Customer customer = bssClient.getCustomer(customerId);
    tenantService.createTenant(tenantId, ...);
  }
}
```

---

### Pattern 4 (DEPRECATED): **Webhook-Based Sync**

**⚠️ DEPRECATED for multi-service datacenters.**

This pattern assumes BSS calls Storage's webhook API directly. In multi-service datacenters, BSS would need to call webhooks for Storage + GPU + VM + Containers, creating tight coupling.

**Use Pull-Based or Event Stream patterns instead.**

---

## Migration & Rollout Strategy

### Phase 1: Define Contracts (Week 1)

**Goal:** Agreement between BSS and Storage teams on responsibilities.

**Deliverables:**
- Signed-off responsibility matrix (see section 2)
- API contract document (OpenAPI spec)
- Test customer accounts for integration testing

**Actions:**
- Joint architecture review meeting
- Document edge cases (customer suspension, quota overrun, etc.)

---

### Phase 2: Build Integration Layer (Week 2-3)

**Storage Team:**
- Implement webhook receiver in Admin Gateway
- Implement OAuth2 filter (validate BSS JWT)
- Implement role mapping (BSS roles → Ozone permissions)
- Add BSS metadata to tenant model

**BSS Team:**
- Implement webhook sender (customer lifecycle events)
- Configure OAuth2/OIDC (issue JWTs with storage claims)
- Implement retry logic for webhook failures

---

### Phase 3: Integration Testing (Week 4)

**Test Scenarios:**
1. BSS creates customer → Ozone tenant created
2. BSS invites user → User can access S3
3. BSS upgrades tier → Ozone quota updated
4. BSS suspends customer → S3 access denied
5. User generates S3 key → Key works with AWS SDK

**Test Environment:**
- Staging BSS + Staging Ozone
- Synthetic customer data
- Automated test suite

---

### Phase 4: Pilot with 5 Customers (Week 5-6)

**Rollout:**
- Select 5 friendly customers
- Monitor webhook success rate
- Monitor S3 error rates
- Collect feedback

**Metrics:**
- Webhook delivery latency (p95 < 1s)
- Webhook failure rate (<1%)
- S3 auth latency (p99 < 100ms)
- Customer onboarding time (target: <5 min)

---

### Phase 5: Full Production Rollout (Week 7+)

**Gradual Rollout:**
- Week 7-8: 10% of customers
- Week 9-10: 50% of customers
- Week 11: 100% of customers

**Rollback Plan:**
- If webhook failure rate >5%: Pause rollout, investigate
- If S3 error rate >1%: Rollback to manual tenant provisioning

---

## Decision Framework

### When to Use Each Architecture

| Scenario | Recommended Architecture | Rationale |
|----------|-------------------------|-----------|
| **BSS manages users/customers only** | Architecture 1 (IdP) | Clean separation, standard OAuth2 |
| **BSS needs compliance control** | Architecture 2 (Dual-layer) | BSS enforces deny rules, Storage allows |
| **BSS wants full S3 control** | Architecture 3 (Full delegation) | ❌ Not recommended (performance, complexity) |
| **BSS has existing RBAC system** | Architecture 1 + Role mapping | Map BSS roles → Ozone permissions |
| **BSS built custom authorization** | Architecture 2 (Federated) | Integrate via policy API |

---

### Decision Tree

```
Start: Does BSS need to manage S3 permissions?
│
├─ No → Use Architecture 1 (BSS as IdP)
│       ✅ Recommended for most cases
│       - BSS: User management, SSO
│       - Storage: S3 authorization
│
└─ Yes → Does BSS need bucket/object-level control?
         │
         ├─ No (just high-level deny) → Use Architecture 2 (Dual-layer)
         │       - BSS: Coarse-grained deny (e.g., compliance zones)
         │       - Storage: Fine-grained allow (s3:GetObject, etc.)
         │
         └─ Yes (full S3 control) → ⚠️ Reconsider!
                 - Is BSS team willing to learn S3 ACL/policy semantics?
                 - Can BSS handle 1000s req/s authorization load?
                 - If yes → Architecture 3 (Full delegation)
                 - If no → Back to Architecture 2
```

---

### Key Questions to Ask BSS Team

Before finalizing architecture, clarify with BSS:

1. **User Management:**
   - ❓ Does BSS already have an IdP (LDAP, AD, Keycloak)?
   - ❓ Can BSS issue OAuth2/OIDC tokens?
   - ❓ What claims can BSS include in JWTs (user_id, customer_id, roles)?

2. **Permission Depth:**
   - ❓ Does BSS need to differentiate s3:GetObject vs s3:PutObject?
   - ❓ Does BSS need bucket-level permissions?
   - ❓ Does BSS need path-based permissions (/logs/*, /data/*)?
   - ❓ Or just: "This user can access this customer's storage" (yes/no)?

3. **Lifecycle Management:**
   - ❓ How does BSS notify Storage of customer changes (webhook, batch, JIT)?
   - ❓ What happens when customer suspends (soft delete, immediate revoke)?
   - ❓ Data retention policy after customer churn?

4. **Compliance:**
   - ❓ Does BSS need to enforce deny rules (PCI zones, GDPR regions)?
   - ❓ Who owns audit logs (BSS aggregates, or Storage provides raw logs)?

5. **Performance:**
   - ❓ Can BSS handle 1000s of authz requests/sec (if Architecture 3)?
   - ❓ What's acceptable latency for authorization (10ms? 100ms?)?

6. **Existing Systems:**
   - ❓ Does BSS already have an RBAC system? What roles exist?
   - ❓ Can Storage extend BSS roles, or must Storage map to own model?

---

## Recommended Approach (TL;DR)

**⚠️ UPDATED FOR MULTI-SERVICE DATACENTERS**

Based on multi-service datacenter requirements (Storage + GPU + VM + Containers):

```
┌─────────────────────────────────────────────────────────────┐
│  RECOMMENDED: Pull-Based or Event-Stream Pattern            │
│  (See BSS_MULTISERVICE_ARCHITECTURE.md for full details)   │
├─────────────────────────────────────────────────────────────┤
│  BSS Team Owns:                                             │
│  - Customer accounts & billing                              │
│  - User directory (LDAP/AD/Keycloak)                        │
│  - High-level roles: CustomerAdmin, Developer, Viewer       │
│  - OAuth2 token issuance with customer_id claim             │
│  - Service entitlements (storage, gpu, vm, container)       │
│  - Central customer registry (GET /customers/{id} API)      │
│  - OR: Event publishing (customer.created to Kafka)         │
│                                                             │
│  Storage Team Owns:                                         │
│  - Tenant provisioning (on-demand or event-triggered)       │
│  - S3 fine-grained permissions (Ranger/SpiceDB)             │
│  - Bucket policies, ACLs, path-based access                 │
│  - S3 access key generation & rotation                      │
│  - S3 audit logs                                            │
│                                                             │
│  Integration (Pull-Based):                                  │
│  1. User makes S3 request → Storage validates JWT           │
│  2. Storage pulls: GET /customers/{id} from BSS             │
│  3. Storage creates tenant on-demand (if entitled)          │
│  4. BSS role → Storage maps (RoleMapper)                    │
│  5. Storage metrics → BSS (usage API for billing)           │
│                                                             │
│  Integration (Event-Stream):                                │
│  1. BSS publishes customer.created event to Kafka           │
│  2. Storage consumes event, creates tenant if entitled      │
│  3. (GPU, VM, Container services do the same independently) │
│  4. User makes S3 request → tenant already exists           │
│  5. BSS role → Storage maps (RoleMapper)                    │
└─────────────────────────────────────────────────────────────┘
```

**Why Pull-Based or Event-Stream?**
- ✅ Works for multi-service datacenters (not just storage)
- ✅ No tight coupling (BSS doesn't call N service APIs)
- ✅ Each service provisions independently
- ✅ Single source of truth (BSS customer registry)
- ✅ Scalable (event-stream) or simple (pull-based)
- ✅ Fault tolerant (hybrid approach)

**Which to choose?**
- **Pull-Based:** Simpler, no message broker needed, good for < 5 services
- **Event-Stream:** More scalable, requires Kafka/RabbitMQ, good for 10+ services
- **Hybrid:** Best reliability (events + pull fallback)

---

## Example Code: BSS Integration

**⚠️ For complete code examples, see [BSS_MULTISERVICE_ARCHITECTURE.md](./BSS_MULTISERVICE_ARCHITECTURE.md).**

### Storage: BSSClient (Pull-Based Pattern)

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
  private final String bssBaseUrl;
  private final String serviceToken;
  private final ObjectMapper mapper = new ObjectMapper();

  // Cache customer metadata (15 min TTL)
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
      return cached;
    }

    // Fetch from BSS
    String url = bssBaseUrl + "/customers/" + customerId;
    String response = Request.Get(url)
        .addHeader("Authorization", "Bearer " + serviceToken)
        .execute()
        .returnContent()
        .asString();

    Customer customer = mapper.readValue(response, Customer.class);
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

    // Getters...
    public String getId() { return id; }
    public String getAdminEmail() { return adminEmail; }
    public String getStatus() { return status; }
    public Map<String, Map<String, Object>> getEntitlements() { return entitlements; }
  }
}
```

### Storage: OAuth2Filter with Pull-Based Tenant Creation

```java
// hadoop-ozone/admin-gateway/.../OAuth2Filter.java
@Override
public void filter(ContainerRequestContext requestContext) {
  DecodedJWT jwt = validateJWT(requestContext);
  String customerId = jwt.getClaim("customer_id").asString();
  String tenantId = "customer-" + customerId;

  // Check if tenant exists locally
  if (!tenantService.tenantExists(tenantId)) {
    LOG.info("Tenant not found, pulling from BSS: {}", tenantId);

    // Pull customer from BSS
    Customer customer = bssClient.getCustomer(customerId);

    // Check status
    if ("suspended".equals(customer.getStatus()) || "deleted".equals(customer.getStatus())) {
      throw new ForbiddenException("Customer account is " + customer.getStatus());
    }

    // Check storage entitlement
    Map<String, Object> storageEnt = customer.getEntitlements().get("storage");
    if (!(Boolean) storageEnt.get("enabled")) {
      throw new ForbiddenException("Storage not enabled for this customer");
    }

    // Create tenant on-demand
    long quotaGB = ((Number) storageEnt.get("quotaGB")).longValue();
    tenantService.createTenant(
      tenantId,
      customer.getAdminEmail(),
      quotaGB * 1024 * 1024 * 1024
    );

    LOG.info("Tenant auto-provisioned: {}", tenantId);
  }

  TenantContext.setTenantId(tenantId);
}
```

### Storage: DEPRECATED Webhook Handler

**⚠️ This webhook approach is DEPRECATED for multi-service datacenters.**

See BSS_MULTISERVICE_ARCHITECTURE.md for event-stream pattern if you need push-based updates.

### BSS: Role Mapping Config

```yaml
# Storage team configuration: bss-role-mapping.yaml
bss_role_mappings:
  CustomerAdmin:
    ozone_role: tenant-admin
    permissions:
      - s3:*
      - admin:ManageTenant
      - admin:ManageUsers
    description: Full control over tenant resources

  Developer:
    ozone_role: tenant-user
    permissions:
      - s3:GetObject
      - s3:PutObject
      - s3:DeleteObject
      - s3:ListBucket
      - s3:CreateBucket
    description: Read/write access to buckets

  Viewer:
    ozone_role: tenant-viewer
    permissions:
      - s3:GetObject
      - s3:ListBucket
    description: Read-only access
```

---

**End of Addendum**

## Next Steps

1. **Schedule alignment meeting** with BSS team
2. **Walk through decision framework** (section 8)
3. **Agree on Architecture** (recommend Architecture 1)
4. **Define API contract** (section 4) in OpenAPI spec
5. **Prototype integration** with one test customer
6. **Iterate based on feedback**

---

**Contact:**
- Storage Team: [your-team@example.com]
- BSS Team: [bss-team@example.com]
- Architecture Review: [arch-review@example.com]
