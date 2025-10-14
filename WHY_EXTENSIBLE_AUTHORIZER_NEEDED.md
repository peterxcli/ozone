# Why the Old Interface Couldn't Support Extensible Authorizers

## Problem Statement

The existing `IAccessAuthorizer` interface was designed for **simple, stateless authorization** but lacks critical features needed for **modern, external authorization systems** like SpiceDB, OPA, or cloud IAM services.

---

## Architecture Comparison

### Old Interface (`IAccessAuthorizer`)

```java
public interface IAccessAuthorizer {
  /**
   * Check access for given ozoneObject.
   */
  boolean checkAccess(IOzoneObj ozoneObject, RequestContext context)
      throws OMException;

  /**
   * @return true for Ozone-native authorizer
   */
  default boolean isNative() {
    return false;
  }
}
```

### What's Missing?

The old interface assumes:
1. ✅ **Stateless operation** - No initialization needed
2. ✅ **Synchronous decisions** - Immediate return
3. ✅ **No external dependencies** - Everything in-process
4. ❌ **No lifecycle management** - Can't start/stop connections
5. ❌ **No health checks** - Can't detect failures
6. ❌ **No metrics** - Can't track performance
7. ❌ **No caching** - Every call hits the backend
8. ❌ **Limited context** - Can't pass OIDC tokens, tenant info

---

## Real-World Problems with Old Interface

### Problem 1: No Lifecycle Management

**Scenario:** You want to use **SpiceDB** (external gRPC service) for authorization.

```java
// ❌ Old interface - WHERE DO WE INITIALIZE THE CLIENT?
public class SpiceDBAuthorizer implements IAccessAuthorizer {
  private SpiceDBClient client;  // When/how to initialize this?

  // No start() method - must initialize in constructor
  public SpiceDBAuthorizer() {
    // ❌ PROBLEM: Constructor gets called by reflection with ConfigurationSource
    // but we can't get config here reliably!
    // this.client = new SpiceDBClient(???);
  }

  @Override
  public boolean checkAccess(IOzoneObj obj, RequestContext ctx) {
    if (client == null) {
      // ❌ Client not initialized! What do we do?
      throw new IllegalStateException("Client not initialized!");
    }
    return client.checkPermission(obj, ctx);
  }

  // ❌ NO WAY TO CLOSE THE CLIENT! Resource leak!
}
```

**Why it fails:**
- Hadoop reflection instantiation: `ReflectionUtils.newInstance(clazz, conf)`
- But constructor is called **before** we can properly configure
- No guaranteed shutdown hook → **resource leaks** (open connections, threads)

**With new interface:**
```java
public class SpiceDBAuthorizer extends BaseAuthorizerPlugin {
  private SpiceDBClient client;

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    // ✅ Called AFTER construction with proper config
    String endpoint = conf.get("ozone.authorizer.spicedb.endpoint");
    this.client = new SpiceDBClient(endpoint);
    this.client.connect();
  }

  @Override
  protected void doStop() throws IOException {
    // ✅ Guaranteed cleanup
    if (client != null) {
      client.close();
    }
  }
}
```

---

### Problem 2: No Performance Optimization (Caching)

**Scenario:** SpiceDB is 50ms away over network. You get 1000 req/s for the same resource.

```java
// ❌ Old interface
public class SpiceDBAuthorizer implements IAccessAuthorizer {
  private SpiceDBClient client;

  @Override
  public boolean checkAccess(IOzoneObj obj, RequestContext ctx) {
    // ❌ EVERY call makes a network request!
    // 1000 req/s × 50ms = 50,000ms of cumulative latency
    // P99 latency will be terrible!
    return client.checkPermission(obj, ctx);  // Network call
  }

  // ❌ No way to cache decisions
  // ❌ If we add a cache, EVERY implementation must duplicate caching logic
  // ❌ Cache invalidation? Manual in every implementation
}
```

**Impact:**
- **Latency**: Every S3 `GetObject` call waits 50ms for authz
- **Cost**: 1000 req/s = 1000 gRPC calls/s to SpiceDB (expensive!)
- **Reliability**: Network blip = all requests fail

**With new interface:**
```java
public class SpiceDBAuthorizer extends BaseAuthorizerPlugin {
  // ✅ Inherits built-in caching from base class
  // ✅ Configurable TTL (default 5s)
  // ✅ Automatic cache invalidation
  // ✅ Cache metrics (hit rate, size, evictions)

  @Override
  protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
    // Only called on CACHE MISS
    // With 80% cache hit rate: 1000 req/s → 200 gRPC calls/s
    return client.checkPermission(obj, ctx);
  }
}
```

**Cache hit example:**
```
Request 1: User alice reads /vol1/bucket1/key1
  → Cache MISS → Call SpiceDB (50ms) → Cache result

Request 2: User alice reads /vol1/bucket1/key1 (1 second later)
  → Cache HIT → Return cached decision (0.1ms)

Result: 500x faster! (50ms → 0.1ms)
```

---

### Problem 3: No Observability (Metrics)

**Scenario:** Production issue - authorization is slow. You need to know WHY.

```java
// ❌ Old interface
public class SpiceDBAuthorizer implements IAccessAuthorizer {
  @Override
  public boolean checkAccess(IOzoneObj obj, RequestContext ctx) {
    return client.checkPermission(obj, ctx);
  }

  // ❌ Questions you CAN'T answer:
  // - How many authz checks per second?
  // - What's the P99 latency?
  // - How many denials? (security incident?)
  // - Which resources are checked most?
  // - Is the cache working?
}
```

**With new interface:**
```java
// ✅ Automatic metrics from BaseAuthorizerPlugin
// Access via JMX:
curl http://om:9874/jmx?qry=Hadoop:service=OzoneManager,name=AuthorizerMetrics

{
  "numAuthChecks": 1500000,          // Total checks
  "numAllowed": 1499500,             // 99.97% allowed
  "numDenied": 500,                  // 0.03% denied (investigate!)
  "numErrors": 0,                    // No errors
  "authCheckLatencyAvgTime": 2.5,   // 2.5ms average (with cache!)
  "authCheckLatencyP99": 45.0,       // 45ms P99 (cache misses)
  "numCacheHits": 1200000,           // 80% cache hit rate
  "numCacheMisses": 300000,          // 20% cache miss rate
  "numReadChecks": 1000000,          // Most checks are reads
  "numWriteChecks": 400000,
  "numBucketChecks": 800000          // Most checks are buckets
}
```

**Real production scenario:**
```
Alert: AuthorizerMetrics.authCheckLatencyP99 > 100ms

Investigation:
1. Check cache hit rate: 80% ✅
2. Check errors: 0 ✅
3. Check SpiceDB latency: 150ms ❌ PROBLEM!

Action: SpiceDB is slow, scale it up or reduce TTL
```

---

### Problem 4: No Health Checks / Circuit Breaking

**Scenario:** SpiceDB goes down. What happens to Ozone?

```java
// ❌ Old interface
public class SpiceDBAuthorizer implements IAccessAuthorizer {
  private SpiceDBClient client;

  @Override
  public boolean checkAccess(IOzoneObj obj, RequestContext ctx) {
    try {
      return client.checkPermission(obj, ctx);
    } catch (IOException e) {
      // ❌ What do we do here?
      // Option 1: Return false → DENY EVERYTHING (bad!)
      // Option 2: Return true → ALLOW EVERYTHING (very bad!)
      // Option 3: Throw exception → ALL REQUESTS FAIL (disaster!)
      // Option 4: Fail-open with logging → security risk

      // No good answer without circuit breaker pattern!
      throw new OMException("SpiceDB unavailable", e);
    }
  }
}
```

**With new interface:**
```java
public class SpiceDBAuthorizer extends BaseAuthorizerPlugin {
  private volatile boolean healthy = true;

  @Override
  protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
    try {
      boolean result = client.checkPermission(obj, ctx);
      healthy = true;  // Mark healthy on success
      return result;
    } catch (IOException e) {
      healthy = false;  // Mark unhealthy
      metrics.recordAuthError();  // Track failures

      // ✅ With health checks, ops can:
      // 1. Monitor authorizer.isHealthy()
      // 2. Set up alerts
      // 3. Implement circuit breaker pattern
      // 4. Fail over to native authorizer

      throw new OMException("SpiceDB check failed", e);
    }
  }

  @Override
  public boolean isHealthy() {
    return healthy && client.isConnected();
  }
}
```

---

### Problem 5: No Modern IAM Context (OIDC, Multi-Tenancy)

**Scenario:** You want to support **OIDC authentication** and **multi-tenancy** (from the design doc).

```java
// ❌ Old interface
public interface IAccessAuthorizer {
  boolean checkAccess(IOzoneObj ozoneObject, RequestContext context);
}

// RequestContext contains:
// - UserGroupInformation (Kerberos UGI)
// - IP address
// - ACL type/rights
// - Owner name

// ❌ Where do we put:
// - OIDC ID token?
// - OIDC claims (groups, roles, email)?
// - Tenant ID?
// - Session token (for temp S3 credentials)?
// - Custom attributes?
```

**Workaround attempts (all bad):**
```java
// ❌ Attempt 1: Hack UGI
UserGroupInformation ugi = context.getClientUgi();
String userName = ugi.getShortUserName();  // "alice"
// How to get tenant? Parse from username? "tenantA$alice" 🤮

// ❌ Attempt 2: Threadlocal (fragile, breaks async)
ThreadLocal<String> TENANT = new ThreadLocal<>();
TENANT.set("tenantA");  // Set by caller
// Breaks with thread pools, async calls

// ❌ Attempt 3: Extend RequestContext (backward incompatible!)
// Can't add fields without breaking existing code
```

**With new interface:**
```java
// ✅ New OzoneAuthorizationContext (wraps RequestContext)
OzoneAuthorizationContext ctx = OzoneAuthorizationContext.builder()
    .requestContext(legacyContext)              // ✅ Backward compat
    .tenantId("acme")                           // ✅ Multi-tenancy
    .sessionToken("eyJhbGci...")                // ✅ OIDC ID token
    .attribute("oidc_subject", "alice@acme.com") // ✅ OIDC claims
    .attribute("oidc_groups", ["admin", "dev"])  // ✅ Groups
    .attribute("tenant_roles", ["owner"])        // ✅ Tenant roles
    .build();

// Now authorizer can make decisions based on:
// - Traditional UGI (Kerberos)
// - Modern OIDC claims
// - Tenant context
// - Session attributes
```

**Real example from design doc:**
```java
// User authenticated via OIDC, gets temp S3 credentials
// S3 Gateway calls authorizer with rich context:

ctx = OzoneAuthorizationContext.builder()
    .requestContext(context)
    .tenantId("acme")  // From S3 access key prefix
    .sessionToken(oidcToken)  // From STS exchange
    .attribute("oidc_subject", "alice@acme.com")
    .attribute("oidc_groups", ["acme-admins"])
    .build();

// SpiceDB authorizer checks:
// 1. Does user alice@acme.com have role in tenant:acme?
// 2. Does group acme-admins have permission on bucket?
// 3. Is the session token still valid?
```

---

### Problem 6: Cannot Compose/Chain Authorizers

**Scenario:** You want to use **SpiceDB for most checks** but **fall back to Native** for `/tmp` shared directory.

```java
// ❌ Old interface - No composition pattern
public class HybridAuthorizer implements IAccessAuthorizer {
  private IAccessAuthorizer spicedb;
  private IAccessAuthorizer nativeAuthz;

  public HybridAuthorizer() {
    // ❌ How to initialize these?
    // ❌ How to start/stop them?
    // ❌ How to aggregate metrics?
  }

  @Override
  public boolean checkAccess(IOzoneObj obj, RequestContext ctx) {
    if (obj.getPath().startsWith("/tmp")) {
      return nativeAuthz.checkAccess(obj, ctx);
    }
    return spicedb.checkAccess(obj, ctx);
  }
}
```

**With new interface:**
```java
// ✅ Can compose plugins with lifecycle management
public class HybridAuthorizer extends BaseAuthorizerPlugin {
  private IAccessAuthorizerPlugin spicedb;
  private IAccessAuthorizerPlugin nativeAuthz;

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    spicedb = new SpiceDBAuthorizer();
    spicedb.start(conf);  // ✅ Proper lifecycle

    nativeAuthz = new OzoneNativeAuthorizer();
    ((OzoneManagerAuthorizer) nativeAuthz).configure(om, km, pm);
  }

  @Override
  protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
    if (obj.getPath().startsWith("/tmp")) {
      return nativeAuthz.checkAccess(obj, ctx);
    }
    return spicedb.checkAccess(obj, ctx);
  }

  @Override
  protected void doStop() throws IOException {
    spicedb.stop();  // ✅ Proper cleanup
  }

  @Override
  public Map<String, Long> getMetrics() {
    // ✅ Aggregate metrics from both
    Map<String, Long> metrics = new HashMap<>();
    metrics.putAll(spicedb.getMetrics());
    metrics.putAll(nativeAuthz.getMetrics());
    return metrics;
  }
}
```

---

## Comparison Table

| Feature | Old `IAccessAuthorizer` | New `IAccessAuthorizerPlugin` |
|---------|-------------------------|-------------------------------|
| **Lifecycle** | ❌ No start/stop | ✅ `start()`, `stop()` with guaranteed cleanup |
| **Connection Management** | ❌ Manual (error-prone) | ✅ Managed in lifecycle hooks |
| **Caching** | ❌ Must implement yourself | ✅ Built-in with configurable TTL |
| **Cache Invalidation** | ❌ Manual | ✅ By resource/subject/tenant |
| **Metrics** | ❌ Must implement yourself | ✅ Auto-collected (decisions, latency, cache) |
| **Health Checks** | ❌ No support | ✅ `isHealthy()`, `getStatus()` |
| **OIDC/Multi-tenant** | ❌ No context support | ✅ `OzoneAuthorizationContext` with tokens/claims |
| **Performance** | ❌ No optimization | ✅ Caching reduces latency 10-500x |
| **Observability** | ❌ Blind operations | ✅ Full metrics via JMX |
| **Composability** | ❌ Hard to chain | ✅ Easy to compose plugins |
| **Resource Leaks** | ❌ Easy to leak | ✅ Guaranteed cleanup in `stop()` |
| **Error Handling** | ❌ No guidance | ✅ Metrics + health checks |
| **Production Ready** | ❌ Need lots of boilerplate | ✅ Base class handles it |

---

## Code Complexity Comparison

### Example: SpiceDB Authorizer

**Old interface (300+ lines of boilerplate):**
```java
public class SpiceDBAuthorizer implements IAccessAuthorizer {
  private SpiceDBClient client;
  private Cache<String, Boolean> cache;
  private AuthorizerMetrics metrics;
  private ScheduledExecutorService cacheCleanup;
  private AtomicBoolean initialized = new AtomicBoolean(false);

  // ❌ No guaranteed initialization point
  // ❌ Manual cache implementation
  // ❌ Manual metrics implementation
  // ❌ Manual resource cleanup
  // ❌ Thread management complexity
  // ❌ Error handling boilerplate

  // ... 300+ lines of infrastructure code ...

  @Override
  public boolean checkAccess(IOzoneObj obj, RequestContext ctx) {
    if (!initialized.get()) {
      throw new IllegalStateException("Not initialized");
    }

    String cacheKey = buildKey(obj, ctx);
    Boolean cached = cache.getIfPresent(cacheKey);
    if (cached != null) {
      metrics.recordCacheHit();
      return cached;
    }

    metrics.recordCacheMiss();
    long start = System.nanoTime();
    try {
      boolean result = client.checkPermission(obj, ctx);
      metrics.recordCheck(result, System.nanoTime() - start);
      cache.put(cacheKey, result);
      return result;
    } catch (Exception e) {
      metrics.recordError();
      throw e;
    }
  }
}
```

**New interface (30 lines of business logic):**
```java
public class SpiceDBAuthorizer extends BaseAuthorizerPlugin {
  private SpiceDBClient client;

  // ✅ Cache: inherited
  // ✅ Metrics: inherited
  // ✅ Lifecycle: inherited
  // ✅ Health: inherited

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    String endpoint = conf.get("ozone.authorizer.spicedb.endpoint");
    client = new SpiceDBClient(endpoint);
    client.connect();
  }

  @Override
  protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
    // Only called on cache miss
    // Metrics/caching handled by base class
    return client.checkPermission(obj, ctx);
  }

  @Override
  protected void doStop() throws IOException {
    client.close();
  }
}
```

**Reduction: 300 lines → 30 lines (90% less code!)**

---

## Backward Compatibility

### How We Maintain 100% Compatibility

**Old authorizers still work unchanged:**
```java
// ✅ OzoneNativeAuthorizer - UNCHANGED
public class OzoneNativeAuthorizer implements OzoneManagerAuthorizer {
  // ... existing code ...
  // No changes needed!
}

// ✅ OzoneAccessAuthorizer - UNCHANGED (no-op authorizer)
public class OzoneAccessAuthorizer implements IAccessAuthorizer {
  // ... existing code ...
  // No changes needed!
}

// ✅ Ranger integration - UNCHANGED
// ozone.acl.authorizer.class=org.apache.ranger...RangerOzoneAuthorizer
// Still works exactly as before!
```

**Factory detects and handles both:**
```java
// OzoneAuthorizerFactory.java
IAccessAuthorizer authorizer = newInstance(clazz, conf);

// ✅ If it's a plugin, start it
if (authorizer instanceof IAccessAuthorizerPlugin) {
  ((IAccessAuthorizerPlugin) authorizer).start(conf);
}

// ✅ If it's old-style OzoneManagerAuthorizer, configure it
if (authorizer instanceof OzoneManagerAuthorizer) {
  ((OzoneManagerAuthorizer) authorizer).configure(om, km, pm);
}

// ✅ Otherwise, just use it as-is
return authorizer;
```

---

## Real-World Use Case: Track B from Design Doc

**Goal:** Integrate SpiceDB for fine-grained ReBAC authorization

### With Old Interface (Would Fail)
```java
public class SpiceDBAuthorizer implements IAccessAuthorizer {
  // ❌ Can't initialize gRPC client properly
  // ❌ Can't cache decisions (50ms latency/call)
  // ❌ Can't track metrics
  // ❌ Can't handle OIDC tokens
  // ❌ Can't do health checks
  // ❌ Resource leaks on shutdown

  // RESULT: Not production-ready!
}
```

### With New Interface (Production Ready)
```java
public class SpiceDBAuthorizer extends BaseAuthorizerPlugin {
  private SpiceDBClient client;

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    // ✅ Initialize gRPC client with config
    String endpoint = conf.get("ozone.authorizer.spicedb.endpoint");
    String token = conf.get("ozone.authorizer.spicedb.token");
    client = new SpiceDBClient(endpoint, token);
    client.connect();
  }

  @Override
  protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
    // ✅ Called only on cache miss
    // ✅ Automatic metrics
    // ✅ 80% cache hit rate → 50ms → 0.1ms
    OzoneObjInfo info = (OzoneObjInfo) obj;
    String resource = buildSpiceDBResource(info);
    String permission = mapPermission(ctx.getAclRights());
    String user = ctx.getClientUgi().getShortUserName();

    return client.checkPermission(resource, permission, user);
  }

  @Override
  protected void doStop() throws IOException {
    // ✅ Guaranteed cleanup
    client.close();
  }

  @Override
  public boolean isHealthy() {
    // ✅ Health monitoring
    return client.isConnected();
  }

  // ✅ Metrics automatically collected:
  // - Decisions (allow/deny)
  // - Latency (P50/P99)
  // - Cache hit rate
  // - Errors
}
```

**Production deployment:**
```bash
# Monitor health
curl http://om:9874/jmx | grep AuthorizerMetrics

# Alert on unhealthy
if ! authorizer.isHealthy(); then
  alert "SpiceDB authorizer unhealthy"
  # Fail over to native authorizer
fi

# Track performance
grafana-dashboard:
  - authz_latency_p99: 2ms (with cache)
  - cache_hit_rate: 85%
  - spicedb_calls_per_sec: 200 (vs 1000 without cache)
```

---

## Summary

The old `IAccessAuthorizer` interface was designed for **simple, in-process authorizers** and fails for **modern, external authorization systems** because:

1. **No lifecycle** → Can't initialize/cleanup resources properly
2. **No caching** → Every call hits the network (slow + expensive)
3. **No metrics** → Can't monitor or debug production issues
4. **No health checks** → Can't detect or handle failures
5. **No modern context** → Can't support OIDC, multi-tenancy, session tokens
6. **Hard to compose** → Can't build hybrid authorizers

The new `IAccessAuthorizerPlugin` interface solves all these problems while maintaining **100% backward compatibility** with existing authorizers.

**Result:** External authorization systems like SpiceDB, OPA, or custom IAM are now **production-ready** with built-in caching, metrics, lifecycle management, and modern IAM context support.
