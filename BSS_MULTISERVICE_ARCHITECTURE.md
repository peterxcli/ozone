# BSS Multi-Service Datacenter Integration Architecture

**Version:** 2.0
**Date:** 2025-10-15
**Parent Document:** BSS_INTEGRATION_ADDENDUM.md
**Status:** Revised Architecture - Multi-Service Datacenter

---

## Problem Restatement

Your datacenter provides **multiple services** (Storage/Ozone, GPU, VM, Containers, etc.), not just storage. When a customer signs up:

- **BSS** manages the customer onboarding **once**
- **BSS** orchestrates resource provisioning **across all services**
- Each individual service (Storage, GPU, VM, etc.) **should NOT** have a "create tenant" API called directly by BSS

**Why the original webhook approach doesn't fit:**
- BSS would need to call N different APIs (one per service)
- Tight coupling between BSS and every service
- No single source of truth for "tenant exists"
- Error handling nightmare (what if GPU service succeeds but Storage fails?)

---

## Revised Architecture Patterns

We now present **two better patterns** for multi-service datacenters:

---

## Architecture A: **Pull-Based with Central Registry (Recommended)**

**Key Idea:** BSS maintains a **central tenant registry**. Each service (Storage, GPU, VM) **pulls tenant metadata** when needed (lazy provisioning).

```
┌─────────────────────────────────────────────────────────────────┐
│                    BSS (Business Support System)                │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Central Tenant Registry (Database)                       │  │
│  │  - Customers (id, name, tier, status, created_at)         │  │
│  │  - Users (id, email, customerId, roles)                   │  │
│  │  - Entitlements per service:                              │  │
│  │    * storage: {enabled: true, quotaGB: 1000}              │  │
│  │    * gpu: {enabled: true, gpuHours: 500}                  │  │
│  │    * vm: {enabled: false}                                 │  │
│  │    * container: {enabled: true, pods: 50}                 │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  BSS REST API (Read-Only for Services)                    │  │
│  │  GET /api/v1/customers/{customerId}                       │  │
│  │  GET /api/v1/customers/{customerId}/entitlements          │  │
│  │  GET /api/v1/users/{userId}                               │  │
│  │  GET /api/v1/users/{userId}/customers (multi-customer)    │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  OAuth2/OIDC Provider (Keycloak)                          │  │
│  │  - Issues JWTs with claims:                               │  │
│  │    {                                                      │  │
│  │      "sub": "alice@acme.com",                             │  │
│  │      "customer_id": "acme-123",                           │  │
│  │      "bss_roles": ["CustomerAdmin"],                      │  │
│  │      "entitlements": {                                    │  │
│  │        "storage": true,                                   │  │
│  │        "gpu": true,                                       │  │
│  │        "vm": false                                        │  │
│  │      }                                                    │  │
│  │    }                                                      │  │
│  └───────────────────────────────────────────────────────────┘  │
└────────────────────┬────────────────────────────────────────────┘
                     │
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
     │ 5. Create local tenant if not exists                 │
     │ 6. Cache customer metadata (15 min TTL)              │
     │                                                       │
     └───────────────────────────────────────────────────────┘
```

---

### How It Works

#### Step 1: Customer Signs Up (BSS Side)

```python
# BSS code: Customer onboarding
def create_customer(customer_data):
    customer = Customer.create(
        id="acme-123",
        name="Acme Corp",
        tier="enterprise",
        entitlements={
            "storage": {"enabled": True, "quotaGB": 1000},
            "gpu": {"enabled": True, "gpuHours": 500},
            "vm": {"enabled": False},
            "container": {"enabled": True, "maxPods": 50}
        }
    )

    # Create admin user in Keycloak
    keycloak.create_user(
        username="admin@acme.com",
        customer_id="acme-123",
        roles=["CustomerAdmin"]
    )

    # That's it! No webhooks to services
    return customer
```

**Key Point:** BSS does NOT call Storage's "create tenant" API. BSS only creates the customer record in its own database.

---

#### Step 2: User Accesses Storage (Storage Side)

```java
// hadoop-ozone/admin-gateway/.../OAuth2Filter.java
@Override
public void filter(ContainerRequestContext requestContext) {
  // 1. Validate JWT from BSS
  DecodedJWT jwt = validateJWT(requestContext);
  String userId = jwt.getSubject();  // alice@acme.com
  String customerId = jwt.getClaim("customer_id").asString();  // acme-123

  // 2. Check if tenant exists locally
  String tenantId = "customer-" + customerId;

  if (!tenantService.tenantExists(tenantId)) {
    LOG.info("Tenant not found locally, pulling from BSS: {}", tenantId);

    // 3. Pull customer metadata from BSS
    Customer customer = bssClient.getCustomer(customerId);

    // 4. Check entitlements
    if (!customer.getEntitlements().get("storage").get("enabled")) {
      throw new ForbiddenException("Storage not enabled for this customer");
    }

    // 5. Create tenant locally (lazy provisioning)
    long quotaBytes = customer.getEntitlements().get("storage").get("quotaGB") * 1024 * 1024 * 1024;
    tenantService.createTenant(
      tenantId,
      customer.getAdminEmail(),
      quotaBytes
    );

    LOG.info("Tenant auto-provisioned: {}", tenantId);
  }

  // 6. Proceed with request
  TenantContext.setTenantId(tenantId);
  TenantContext.setUserId(userId);
}
```

**Key Point:** Storage creates the tenant **on-demand** when the first user request arrives, **NOT** via a BSS webhook.

---

#### Step 3: BSS Client for Storage

```java
// hadoop-ozone/admin-gateway/.../BSSClient.java
package org.apache.ozone.admin.bss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

@Singleton
public class BSSClient {
  private static final Logger LOG = LoggerFactory.getLogger(BSSClient.class);

  private final String bssBaseUrl;
  private final String serviceToken;  // M2M token for Storage → BSS calls
  private final ObjectMapper mapper = new ObjectMapper();

  // Cache customer metadata to avoid excessive BSS calls
  private final Cache<String, Customer> customerCache = CacheBuilder.newBuilder()
      .expireAfterWrite(15, TimeUnit.MINUTES)
      .maximumSize(10000)
      .build();

  @Inject
  public BSSClient(ConfigurationSource conf) {
    this.bssBaseUrl = conf.get("ozone.bss.api.url", "https://bss.example.com/api/v1");
    this.serviceToken = conf.get("ozone.bss.service.token");  // Pre-shared service account token
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

    // Getters/setters...

    public String getId() { return id; }
    public String getName() { return name; }
    public String getTier() { return tier; }
    public String getStatus() { return status; }
    public String getAdminEmail() { return adminEmail; }
    public Map<String, Map<String, Object>> getEntitlements() { return entitlements; }
  }
}
```

---

### Pros & Cons

**Pros:**
- ✅ **Decoupled:** Storage doesn't wait for BSS webhooks
- ✅ **No sync lag:** Tenant created exactly when needed
- ✅ **Single source of truth:** BSS is authoritative for customer data
- ✅ **Fault tolerant:** If BSS is down briefly, cached data still works
- ✅ **Scalable:** Each service pulls independently, no orchestration needed

**Cons:**
- ⚠️ **First request latency:** Adds BSS API call (50-200ms) on first access
- ⚠️ **BSS dependency:** Storage needs BSS to be available (mitigated by caching)
- ⚠️ **Cache invalidation:** Customer metadata changes (tier upgrade, suspension) may be delayed

---

## Architecture B: **Event Stream with Service-Owned State**

**Key Idea:** BSS publishes customer lifecycle events to a **message broker** (Kafka, RabbitMQ, NATS). Each service subscribes and maintains its own tenant state.

```
┌─────────────────────────────────────────────────────────────────┐
│                    BSS (Business Support System)                │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Customer Database                                        │  │
│  └────────────────┬──────────────────────────────────────────┘  │
│                   │                                              │
│  ┌────────────────▼──────────────────────────────────────────┐  │
│  │  Event Publisher                                          │  │
│  │  - customer.created                                       │  │
│  │  - customer.updated (tier change)                         │  │
│  │  - customer.suspended                                     │  │
│  │  - customer.deleted                                       │  │
│  │  - user.added_to_customer                                 │  │
│  │  - user.removed_from_customer                             │  │
│  └────────────────┬──────────────────────────────────────────┘  │
└───────────────────┼──────────────────────────────────────────────┘
                    │
                    ▼ Publishes to
┌─────────────────────────────────────────────────────────────────┐
│               Kafka / RabbitMQ / NATS / Cloud Pub/Sub           │
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

---

### How It Works

#### Step 1: BSS Publishes Customer Created Event

```python
# BSS code: After customer creation
def create_customer(customer_data):
    customer = Customer.create(
        id="acme-123",
        name="Acme Corp",
        tier="enterprise",
        entitlements={...}
    )

    # Publish event to message broker
    event = {
        "event_type": "customer.created",
        "timestamp": datetime.utcnow().isoformat(),
        "customer": {
            "id": customer.id,
            "name": customer.name,
            "tier": customer.tier,
            "admin_email": customer.admin_email,
            "entitlements": customer.entitlements
        }
    }

    kafka.publish("bss.customer.lifecycle", json.dumps(event))

    return customer
```

---

#### Step 2: Storage Consumes Event

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
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public BSSEventConsumer(ConfigurationSource conf, TenantService tenantService) {
    this.tenantService = tenantService;

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
          // Retry or dead-letter queue
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
}
```

---

### Pros & Cons

**Pros:**
- ✅ **Decoupled:** Services don't depend on BSS at runtime
- ✅ **Real-time:** Tenants created within seconds of customer signup
- ✅ **Reliable:** Message broker handles retries, dead-letter queues
- ✅ **Audit trail:** Events are stored (Kafka retention)
- ✅ **Scalable:** Asynchronous, non-blocking

**Cons:**
- ⚠️ **Infrastructure:** Requires message broker (Kafka, RabbitMQ, etc.)
- ⚠️ **Eventual consistency:** Tenant creation lags customer creation by a few seconds
- ⚠️ **Complexity:** Need to handle event ordering, idempotency, retries
- ⚠️ **Operational overhead:** Monitoring consumer lag, dead-letter queues

---

## Comparison: Architecture A vs B

| Aspect | Architecture A (Pull-Based) | Architecture B (Event Stream) |
|--------|----------------------------|------------------------------|
| **Tenant Creation Trigger** | First user request (lazy) | BSS event (immediate) |
| **Latency** | First request +50-200ms | Eventual (1-5 seconds lag) |
| **BSS Dependency** | Runtime (mitigated by cache) | Design-time only |
| **Infrastructure** | HTTP client | Message broker (Kafka/RabbitMQ) |
| **Complexity** | Low | Medium |
| **Consistency** | Strong (BSS is source of truth) | Eventual (events lag) |
| **Failure Mode** | BSS down → can't create new tenants | Message broker down → no new tenants |
| **Best For** | Simpler setup, fewer services | Large-scale, many services |

---

## Recommended Approach

### For Your Datacenter:

**If you have < 5 services and want simplicity:**
→ Use **Architecture A (Pull-Based)**
- Easiest to implement
- No message broker needed
- Good enough for most cases

**If you have 10+ services or already use Kafka:**
→ Use **Architecture B (Event Stream)**
- More scalable
- Better for microservices architecture
- Cleaner separation of concerns

---

## Hybrid Approach (Best of Both Worlds)

Combine both patterns:

1. **BSS publishes events** (Architecture B) for **proactive provisioning**
2. **Services also pull on-demand** (Architecture A) as a **fallback**

```java
@Override
public void filter(ContainerRequestContext requestContext) {
  DecodedJWT jwt = validateJWT(requestContext);
  String customerId = jwt.getClaim("customer_id").asString();
  String tenantId = "customer-" + customerId;

  // Try local cache/database first
  if (!tenantService.tenantExists(tenantId)) {
    // Fallback: Maybe event consumer is lagging, pull from BSS
    LOG.warn("Tenant not found locally (event lag?), pulling from BSS: {}", tenantId);

    Customer customer = bssClient.getCustomer(customerId);
    tenantService.createTenant(tenantId, customer.getAdminEmail(), customer.getQuota());
  }

  TenantContext.setTenantId(tenantId);
}
```

**Benefits:**
- Normal case: Events create tenants proactively (low latency)
- Edge case: Pull creates tenant on-demand (fault tolerance)
- Best reliability and performance

---

## Configuration Example

### Architecture A (Pull-Based)

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
```

### Architecture B (Event Stream)

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

---

## Next Steps

1. **Clarify with BSS team:**
   - Does BSS already have a message broker (Kafka, RabbitMQ)?
   - Is BSS willing to expose a GET /customers/{id} API?
   - What's the expected customer onboarding volume (10/day vs 1000/day)?

2. **Choose architecture:**
   - Small datacenter → Architecture A (Pull-Based)
   - Large datacenter with Kafka → Architecture B (Event Stream)
   - High availability requirement → Hybrid

3. **Prototype:**
   - Implement BSSClient for Architecture A
   - Or implement BSSEventConsumer for Architecture B
   - Test with 1-2 test customers

---

**End of Document**
