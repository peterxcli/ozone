# IAM Design Documentation - Reading Guide

**Last Updated:** 2025-10-15

This directory contains comprehensive IAM (Identity and Access Management) design documentation for Apache Ozone S3 Gateway with multi-tenancy support.

---

## 📚 Document Overview

### 1. **IAM_DESIGN.md** - Main IAM Architecture
**Start here if:** You're implementing the core IAM system for Ozone.

**Contents:**
- Complete IAM architecture with OAuth2 authentication
- Ranger MVP implementation (2-3 weeks)
- SpiceDB migration plan (for scale)
- AWS S3 compatibility layer (bucket policies, ACLs)
- Code examples: OAuth2Filter, TenantResource, AccessKeyResource
- Build & configuration instructions
- Testing & rollout strategy

**Key Sections:**
- Architecture Design (current state + MVP + target)
- MVP Implementation with Ranger (OAuth2, Tenant APIs, Ranger integration)
- SpiceDB Migration (schema, relationship tuples, 4-phase migration)
- AWS ACL/Policy Compatibility Layer
- Security & Compliance Checklist

**Use Cases:**
- Implementing OAuth2 authentication for Admin Gateway
- Setting up Ranger policies for multi-tenancy
- Planning migration to SpiceDB
- Adding AWS S3 bucket policy support

---

### 2. **BSS_MULTISERVICE_ARCHITECTURE.md** - Multi-Service Datacenter Integration
**Start here if:** Your datacenter provides multiple services (Storage, GPU, VM, Containers) and BSS manages them all.

**Contents:**
- Pull-Based pattern (recommended for simplicity)
- Event-Stream pattern (recommended for scale)
- Hybrid approach (best reliability)
- Complete BSSClient and BSSEventConsumer code
- Configuration examples

**Key Sections:**
- Architecture A: Pull-Based (lazy tenant provisioning)
- Architecture B: Event-Stream (Kafka/RabbitMQ-based)
- Comparison table (Pull vs Event vs Hybrid)
- Implementation code for both patterns

**Use Cases:**
- BSS manages customer onboarding for Storage + GPU + VM + Containers
- Avoiding tight coupling between BSS and individual services
- Choosing between pull-based (simple) and event-stream (scalable)
- Implementing just-in-time tenant provisioning

---

### 3. **BSS_INTEGRATION_ADDENDUM.md** - Team Boundaries & Patterns (Legacy)
**Start here if:** You want to understand team responsibilities and authorization delegation models.

**Contents:**
- Team responsibility boundaries (BSS vs Storage)
- Authorization delegation models (JWT claims, role mapping, federated)
- Role mapping configuration
- API contracts
- Historical webhook patterns (deprecated for multi-service)

**Key Sections:**
- Team Responsibilities & Boundaries (still valid)
- Authorization Delegation Models (JWT, role mapping, federated)
- Role Mapping Config (bss-role-mapping.yaml)
- Decision Framework

**Use Cases:**
- Clarifying what BSS owns vs what Storage owns
- Designing role mapping (CustomerAdmin → Tenant Admin)
- Understanding authorization delegation options
- Historical reference for webhook-based integration (now deprecated)

**⚠️ Note:** The webhook-based patterns in this doc are deprecated for multi-service datacenters. See BSS_MULTISERVICE_ARCHITECTURE.md instead.

---

## 🚀 Quick Start Guide

### Scenario 1: "I'm building the IAM system from scratch"
1. Read **IAM_DESIGN.md** - Focus on "MVP Implementation with Ranger"
2. Follow the Quick Start Guide (IAM_DESIGN.md:1910-1993)
3. Build OAuth2Filter and TenantResource
4. Configure Ranger integration

### Scenario 2: "I need to integrate with BSS team (multi-service datacenter)"
1. Read **BSS_MULTISERVICE_ARCHITECTURE.md** - Choose Pull-Based or Event-Stream
2. Read **BSS_INTEGRATION_ADDENDUM.md** - Understand team boundaries
3. Implement BSSClient (pull-based) or BSSEventConsumer (event-stream)
4. Update OAuth2Filter to pull customer metadata on-demand

### Scenario 3: "I want to migrate to SpiceDB later"
1. Read **IAM_DESIGN.md** - Focus on "SpiceDB Migration Architecture"
2. Implement Ranger MVP first (don't skip!)
3. Follow 4-phase migration plan (dual-write → dual-read → cutover → deprecate)
4. Use hybrid approach during transition

### Scenario 4: "I need AWS S3 bucket policy support"
1. Read **IAM_DESIGN.md** - Focus on "AWS ACL/Policy Compatibility Layer"
2. Implement BucketPolicy model and S3PolicyEvaluator
3. Add bucket policy REST endpoints
4. Translate bucket policies to Ranger/SpiceDB policies

---

## 🎯 Decision Framework

### Question 1: Does BSS manage multiple services (Storage + GPU + VM)?
- **Yes** → Read **BSS_MULTISERVICE_ARCHITECTURE.md** first
- **No (storage only)** → Read **BSS_INTEGRATION_ADDENDUM.md** (webhook pattern may be acceptable)

### Question 2: How many services does your datacenter have?
- **< 5 services, simple setup** → Use Pull-Based pattern (BSS_MULTISERVICE_ARCHITECTURE.md)
- **10+ services, already have Kafka** → Use Event-Stream pattern (BSS_MULTISERVICE_ARCHITECTURE.md)
- **Need highest reliability** → Use Hybrid pattern (BSS_MULTISERVICE_ARCHITECTURE.md)

### Question 3: What's your scale requirement?
- **< 500 tenants, low complexity** → Ranger MVP (IAM_DESIGN.md)
- **1M+ tenants, high throughput** → Plan SpiceDB migration (IAM_DESIGN.md)

### Question 4: Do you need AWS S3 compatibility?
- **Yes (bucket policies, ACLs)** → Read AWS Compatibility Layer (IAM_DESIGN.md)
- **No (Ranger/SpiceDB only)** → Focus on MVP Implementation (IAM_DESIGN.md)

---

## 📋 Implementation Checklist

### Phase 1: Core IAM (Weeks 1-3)
- [ ] Read IAM_DESIGN.md - MVP Implementation section
- [ ] Implement OAuth2Filter (JWT validation)
- [ ] Implement TenantResource (tenant CRUD)
- [ ] Configure Ranger integration
- [ ] Setup Keycloak for OAuth2/OIDC
- [ ] Test with 1-2 test tenants

### Phase 2: BSS Integration (Weeks 2-4, parallel with Phase 1)
- [ ] Read BSS_MULTISERVICE_ARCHITECTURE.md
- [ ] Choose pattern: Pull-Based or Event-Stream
- [ ] Implement BSSClient (pull) or BSSEventConsumer (event)
- [ ] Define API contract with BSS team
- [ ] Test customer onboarding flow

### Phase 3: AWS Compatibility (Weeks 4-6, optional)
- [ ] Read AWS ACL/Policy Compatibility Layer (IAM_DESIGN.md)
- [ ] Implement BucketPolicy model
- [ ] Implement S3PolicyEvaluator
- [ ] Add bucket policy REST endpoints
- [ ] Test with AWS SDK

### Phase 4: SpiceDB Migration (Months 3-6, future)
- [ ] Read SpiceDB Migration Architecture (IAM_DESIGN.md)
- [ ] Setup SpiceDB cluster
- [ ] Implement dual-write (Ranger + SpiceDB)
- [ ] Run dual-read comparison (detect drift)
- [ ] Cutover to SpiceDB
- [ ] Deprecate Ranger

---

## 🔍 Key Code Files Reference

| Component | Location | Document Reference |
|-----------|----------|-------------------|
| **OAuth2Filter** | `hadoop-ozone/admin-gateway/.../auth/OAuth2Filter.java` | IAM_DESIGN.md:383-484 |
| **TenantResource** | `hadoop-ozone/admin-gateway/.../iam/TenantResource.java` | IAM_DESIGN.md:486-609 |
| **TenantService** | `hadoop-ozone/admin-gateway/.../service/TenantService.java` | IAM_DESIGN.md:612-713 |
| **AccessKeyResource** | `hadoop-ozone/admin-gateway/.../iam/AccessKeyResource.java` | IAM_DESIGN.md:716-802 |
| **BSSClient** | `hadoop-ozone/admin-gateway/.../bss/BSSClient.java` | BSS_MULTISERVICE_ARCHITECTURE.md |
| **BSSEventConsumer** | `hadoop-ozone/admin-gateway/.../bss/BSSEventConsumer.java` | BSS_MULTISERVICE_ARCHITECTURE.md |
| **SpiceDBAuthzService** | `hadoop-ozone/ozone-manager/.../auth/SpiceDBAuthzService.java` | IAM_DESIGN.md:1243-1358 |
| **RangerClientMultiTenantAccessController** | `hadoop-ozone/ozone-manager/.../multitenant/RangerClientMultiTenantAccessController.java` | Existing code |

---

## 📞 Contact & Questions

**For Storage Team:**
- IAM architecture questions → Reference IAM_DESIGN.md
- BSS integration questions → Reference BSS_MULTISERVICE_ARCHITECTURE.md
- Team boundary questions → Reference BSS_INTEGRATION_ADDENDUM.md

**For BSS Team:**
- API contract → BSS_MULTISERVICE_ARCHITECTURE.md (Pull-Based section)
- Event schema → BSS_MULTISERVICE_ARCHITECTURE.md (Event-Stream section)
- Entitlements model → BSS_INTEGRATION_ADDENDUM.md (Responsibility Boundaries)

---

## 🔄 Document Version History

- **v2.0 (2025-10-15):** Added BSS_MULTISERVICE_ARCHITECTURE.md for multi-service datacenters
- **v1.0 (2025-10-14):** Initial IAM_DESIGN.md and BSS_INTEGRATION_ADDENDUM.md

---

**Ready to start? Begin with IAM_DESIGN.md or BSS_MULTISERVICE_ARCHITECTURE.md based on your scenario above.**
