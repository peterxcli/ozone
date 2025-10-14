# Ozone Extensible Authorization Framework

This document explains how to implement custom authorization plugins for Apache Ozone.

## Overview

Ozone supports pluggable authorization through the `IAccessAuthorizer` interface hierarchy:

```
IAccessAuthorizer (base interface)
  ├── OzoneManagerAuthorizer (OM-specific, with configure())
  └── IAccessAuthorizerPlugin (with lifecycle: start/stop)
        └── BaseAuthorizerPlugin (abstract base with caching/metrics)
```

## Quick Start

### 1. Using an Existing Authorizer

**Native Authorizer** (default):
```xml
<property>
  <name>ozone.acl.enabled</name>
  <value>true</value>
</property>
<property>
  <name>ozone.acl.authorizer.class</name>
  <value>org.apache.hadoop.ozone.security.acl.OzoneNativeAuthorizer</value>
</property>
```

**SpiceDB Authorizer** (example external ReBAC):
```xml
<property>
  <name>ozone.acl.authorizer.class</name>
  <value>org.apache.hadoop.ozone.security.acl.SpiceDBAuthorizer</value>
</property>
<property>
  <name>ozone.authorizer.spicedb.endpoint</name>
  <value>spicedb.example.com:50051</value>
</property>
<property>
  <name>ozone.authorizer.spicedb.token</name>
  <value>YOUR_SPICEDB_TOKEN</value>
</property>
<property>
  <name>ozone.authorizer.cache.enabled</name>
  <value>true</value>
</property>
<property>
  <name>ozone.authorizer.cache.ttl.ms</name>
  <value>5000</value>
</property>
```

### 2. Implementing a Custom Authorizer

#### Option A: Simple Authorizer (no lifecycle)

Implement `IAccessAuthorizer` directly:

```java
public class MySimpleAuthorizer implements IAccessAuthorizer {
  @Override
  public boolean checkAccess(IOzoneObj ozoneObject, RequestContext context) {
    // Your authorization logic here
    return true;
  }
}
```

#### Option B: Plugin with Lifecycle (recommended)

Extend `BaseAuthorizerPlugin` for built-in caching, metrics, and lifecycle:

```java
public class MyCustomAuthorizer extends BaseAuthorizerPlugin {

  private MyAuthClient client;

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    String endpoint = conf.get("ozone.authorizer.mycustom.endpoint");
    this.client = new MyAuthClient(endpoint);
    this.client.connect();
  }

  @Override
  protected boolean doCheckAccess(IOzoneObj ozoneObject,
                                   RequestContext context) {
    String user = context.getClientUgi().getShortUserName();
    String resource = buildResourcePath(ozoneObject);
    String action = context.getAclRights().name();

    return client.checkPermission(user, resource, action);
  }

  @Override
  protected void doStop() throws IOException {
    if (client != null) {
      client.close();
    }
  }
}
```

### 3. Configuration

```xml
<!-- Enable ACLs -->
<property>
  <name>ozone.acl.enabled</name>
  <value>true</value>
</property>

<!-- Set your authorizer -->
<property>
  <name>ozone.acl.authorizer.class</name>
  <value>com.example.MyCustomAuthorizer</value>
</property>

<!-- Decision caching (recommended for external authz) -->
<property>
  <name>ozone.authorizer.cache.enabled</name>
  <value>true</value>
</property>
<property>
  <name>ozone.authorizer.cache.max.size</name>
  <value>10000</value>
</property>
<property>
  <name>ozone.authorizer.cache.ttl.ms</name>
  <value>5000</value>
</property>

<!-- Metrics -->
<property>
  <name>ozone.authorizer.metrics.enabled</name>
  <value>true</value>
</property>
```

## Features

### Built-in Decision Caching

`BaseAuthorizerPlugin` provides automatic caching with:
- Configurable TTL (default: 5 seconds)
- LRU eviction
- Cache invalidation by resource/subject/tenant
- Cache hit/miss metrics

Access the cache in your subclass:
```java
// Invalidate cache when policies change
invalidateResource("/volume1/bucket1");
invalidateSubject("user:alice");

// Get cache stats
CacheStats stats = getStats();
LOG.info("Cache hit rate: {}%", stats.getHitRate() * 100);
```

### Metrics Collection

Automatic metrics for:
- Total authorization checks
- Allow/deny decisions
- Authorization latency (p50, p99, etc.)
- Cache hit/miss rates
- Per-action metrics (READ, WRITE, DELETE)
- Per-resource metrics (VOLUME, BUCKET, KEY)

Access via Hadoop metrics system:
```bash
curl http://om-host:9874/jmx?qry=Hadoop:service=OzoneManager,name=AuthorizerMetrics
```

### Health Checks

```java
if (!authorizer.isHealthy()) {
  LOG.error("Authorizer unhealthy: {}", authorizer.getStatus());
}
```

## Advanced Features

### Multi-Tenancy Support

Use `OzoneAuthorizationContext` to pass tenant information:

```java
OzoneAuthorizationContext ctx = OzoneAuthorizationContext.builder()
    .requestContext(legacyContext)
    .tenantId("acme")
    .sessionToken("eyJhbGci...")
    .attribute("oidc_subject", "user@example.com")
    .attribute("oidc_groups", Arrays.asList("admin", "developers"))
    .build();

// Extract in your authorizer
String tenant = ctx.getTenantId().orElse("default");
String oidcSubject = ctx.getAttribute("oidc_subject", String.class)
    .orElse(null);
```

### Custom Cache Keys

Override `buildCacheKey()` to include tenant/session context:

```java
@Override
protected CacheKey buildCacheKey(IOzoneObj obj, RequestContext context) {
  String sessionToken = extractSessionToken(context);
  String tenantId = extractTenantId(obj);

  return CacheKey.builder()
      .subject(context.getClientUgi().getShortUserName())
      .resourcePath(buildResourcePath(obj))
      .action(context.getAclRights().name())
      .tenantId(tenantId)
      .sessionTokenHash(hash(sessionToken))
      .build();
}
```

### Policy Refresh

Implement dynamic policy refresh:

```java
@Override
public void refresh() throws OMException {
  LOG.info("Refreshing policies from external source");
  client.reloadPolicies();
  clear(); // Clear cache after policy update
}
```

## Integration Examples

### Example 1: Open Policy Agent (OPA)

```java
public class OPAAuthorizer extends BaseAuthorizerPlugin {
  private OpaClient client;

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    String url = conf.get("ozone.authorizer.opa.url");
    this.client = new OpaClient(url);
  }

  @Override
  protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
    Map<String, Object> input = Map.of(
        "user", ctx.getClientUgi().getShortUserName(),
        "action", ctx.getAclRights().name(),
        "resource", buildResourcePath(obj)
    );

    // POST to OPA: {"input": {...}}
    JsonNode result = client.evaluate("ozone/authz/allow", input);
    return result.get("result").asBoolean();
  }

  @Override
  protected void doStop() throws IOException {
    client.close();
  }
}
```

### Example 2: AWS IAM-style Policies

```java
public class IAMPolicyAuthorizer extends BaseAuthorizerPlugin {
  private PolicyEngine engine;

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    String policyFile = conf.get("ozone.authorizer.iam.policy.file");
    this.engine = PolicyEngine.loadFromFile(policyFile);
  }

  @Override
  protected boolean doCheckAccess(IOzoneObj obj, RequestContext ctx) {
    String principal = "user:" + ctx.getClientUgi().getShortUserName();
    String resource = "arn:ozone:" + buildResourcePath(obj);
    String action = "ozone:" + ctx.getAclRights().name();

    return engine.evaluate(principal, action, resource);
  }

  @Override
  protected void doStop() {
    // No-op
  }
}
```

## Testing

### Unit Testing

```java
@Test
public void testCustomAuthorizer() throws Exception {
  Configuration conf = new OzoneConfiguration();
  conf.set("ozone.authorizer.mycustom.endpoint", "localhost:9000");

  MyCustomAuthorizer authorizer = new MyCustomAuthorizer();
  authorizer.start(conf);

  try {
    OzoneObjInfo obj = OzoneObjInfo.Builder.newBuilder()
        .setVolumeName("vol1")
        .setBucketName("bucket1")
        .setKeyName("key1")
        .setResType(OzoneObj.ResourceType.KEY)
        .build();

    RequestContext ctx = RequestContext.newBuilder()
        .setClientUgi(UserGroupInformation.createRemoteUser("alice"))
        .setAclRights(IAccessAuthorizer.ACLType.READ)
        .build();

    boolean allowed = authorizer.checkAccess(obj, ctx);
    assertTrue(allowed);

    // Verify metrics
    assertTrue(authorizer.getMetrics().get("total_checks") > 0);

  } finally {
    authorizer.stop();
  }
}
```

### Integration Testing

```java
@Test
public void testAuthorizerInOM() throws Exception {
  OzoneConfiguration conf = new OzoneConfiguration();
  conf.setClass(OZONE_ACL_AUTHORIZER_CLASS,
      MyCustomAuthorizer.class, IAccessAuthorizer.class);
  conf.setBoolean(OZONE_ACL_ENABLED, true);

  MiniOzoneCluster cluster = MiniOzoneCluster.newBuilder(conf)
      .build();
  cluster.waitForClusterToBeReady();

  try {
    OzoneClient client = cluster.newClient();
    // Perform operations and verify authz...
  } finally {
    cluster.shutdown();
  }
}
```

## Production Checklist

- [ ] Implement proper error handling and retries
- [ ] Configure appropriate cache TTLs for your use case
- [ ] Enable metrics and set up monitoring/alerting
- [ ] Implement health checks for your backing service
- [ ] Add circuit breakers for external authz failures
- [ ] Configure TLS for external connections
- [ ] Set up policy synchronization (Ozone → authz system)
- [ ] Test failover scenarios (authz service down)
- [ ] Document operational runbooks
- [ ] Load test with realistic workloads

## Troubleshooting

### Authorizer not loading
```
ERROR OzoneAuthorizerFactory: Failed to start authorizer plugin
```
- Check `ozone.acl.authorizer.class` is correct
- Verify class is on classpath
- Check constructor accepts `ConfigurationSource`

### High authorization latency
- Enable caching: `ozone.authorizer.cache.enabled=true`
- Increase cache size: `ozone.authorizer.cache.max.size=50000`
- Reduce TTL for less stale data vs performance tradeoff
- Check network latency to external authz service
- Review metrics: `cache_hit_rate`, `auth_check_latency_p99`

### Cache stampede
- BaseAuthorizerPlugin uses synchronized access to prevent thundering herd
- Consider adding jitter to cache TTLs in high-concurrency scenarios

## References

- [Ozone Security Documentation](https://ozone.apache.org/docs/security/)
- [Ranger Integration](https://ozone.apache.org/docs/security/ranger/)
- [SpiceDB](https://authzed.com/docs)
- [Open Policy Agent](https://www.openpolicyagent.org/)
