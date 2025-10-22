# Ozone STS + Keycloak Architecture

This document provides a technical deep dive into the integration architecture of Apache Ozone's Security Token Service (STS) with Keycloak as an OIDC identity provider.

## Table of Contents

- [System Architecture](#system-architecture)
- [Authentication Flow](#authentication-flow)
- [Token Exchange Sequence](#token-exchange-sequence)
- [Authorization Decision Flow](#authorization-decision-flow)
- [Component Integration](#component-integration)
- [Security Considerations](#security-considerations)
- [Performance and Scalability](#performance-and-scalability)

## System Architecture

### High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                         User/Client                           │
└─────────────────┬────────────────────────────────────────────┘
                  │
                  │ 1. Authenticate
                  ▼
┌──────────────────────────────────────────────────────────────┐
│                        Keycloak                               │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  • User Management                                      │ │
│  │  • OIDC/SAML Provider                                   │ │
│  │  • JWT Token Generation                                 │ │
│  │  • Group/Role Management                                │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────┬────────────────────────────────────────────┘
                  │ 2. OIDC Token (JWT)
                  ▼
┌──────────────────────────────────────────────────────────────┐
│                    Ozone S3 Gateway                           │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                    STS Endpoint                         │ │
│  │  ┌──────────────────────────────────────────────────┐  │ │
│  │  │  Authentication Providers                         │  │ │
│  │  │  • IDPAuthenticationProvider (OIDC/SAML)          │  │ │
│  │  │  • LDAPAuthenticationProvider                     │  │ │
│  │  │  • ADAuthenticationProvider                       │  │ │
│  │  │  • RestAPIAuthenticationProvider                  │  │ │
│  │  └──────────────────────────────────────────────────┘  │ │
│  │  ┌──────────────────────────────────────────────────┐  │ │
│  │  │  Token Management                                 │  │ │
│  │  │  • JWTTokenGenerator                              │  │ │
│  │  │  • TokenValidator                                 │  │ │
│  │  │  • TokenStore (Cache)                             │  │ │
│  │  └──────────────────────────────────────────────────┘  │ │
│  │  ┌──────────────────────────────────────────────────┐  │ │
│  │  │  Credential Generation                            │  │ │
│  │  │  • STSCredentialGenerator                         │  │ │
│  │  │  • TemporaryCredentials                           │  │ │
│  │  └──────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                    S3 API Handler                      │ │
│  │  • Bucket operations                                   │ │
│  │  • Object operations                                   │ │
│  │  • Authorization enforcement                           │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────┬────────────────────────────────────────────┘
                  │ 3. S3 Operations
                  ▼
┌──────────────────────────────────────────────────────────────┐
│                   Ozone Storage Layer                         │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐ │
│  │     OM      │  │     SCM     │  │     DataNodes        │ │
│  │  (Metadata) │  │  (Storage)  │  │   (Data Storage)     │ │
│  └─────────────┘  └─────────────┘  └──────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### Component Breakdown

#### 1. Keycloak (Identity Provider)
- **Role**: OIDC/SAML identity provider
- **Responsibilities**:
  - User authentication
  - JWT token generation
  - User/group/role management
  - Token lifecycle (issuance, refresh, revocation)
- **Database**: PostgreSQL for persistence

#### 2. Ozone S3 Gateway with STS
- **Role**: S3-compatible API with token service
- **Responsibilities**:
  - OIDC token validation
  - Temporary credential generation
  - S3 API request handling
  - Authorization enforcement

#### 3. Ozone Storage Cluster
- **Components**:
  - **OM (OzoneManager)**: Namespace management
  - **SCM (StorageContainerManager)**: Storage orchestration
  - **DataNodes**: Actual data storage

## Authentication Flow

### Detailed Authentication Sequence

```
┌──────┐         ┌──────────┐         ┌─────────┐
│Client│         │Keycloak  │         │   STS   │
└──┬───┘         └────┬─────┘         └────┬────┘
   │                  │                    │
   │ 1. POST /token   │                    │
   │ (username/pwd)   │                    │
   ├─────────────────>│                    │
   │                  │                    │
   │ 2. Validate      │                    │
   │    credentials   │                    │
   │<─────────────────┤                    │
   │                  │                    │
   │ 3. Generate JWT  │                    │
   │    (with claims) │                    │
   │<─────────────────┤                    │
   │                  │                    │
   │ 4. JWT Token     │                    │
   │ {                │                    │
   │   "sub": "user", │                    │
   │   "groups": [],  │                    │
   │   "roles": [],   │                    │
   │   "exp": 12345   │                    │
   │ }                │                    │
   │                  │                    │
   └──────────────────┴────────────────────┘
```

### JWT Token Structure

```json
{
  "header": {
    "alg": "RS256",
    "typ": "JWT",
    "kid": "key-id"
  },
  "payload": {
    "exp": 1234567890,
    "iat": 1234567590,
    "jti": "unique-token-id",
    "iss": "http://keycloak:8080/realms/ozone",
    "aud": "ozone-s3",
    "sub": "user-uuid",
    "typ": "Bearer",
    "azp": "ozone-s3",
    "preferred_username": "admin",
    "email": "admin@example.com",
    "email_verified": true,
    "groups": ["/admins"],
    "roles": ["s3-admin", "s3-full-access"]
  },
  "signature": "..."
}
```

## Token Exchange Sequence

### STS AssumeRoleWithWebIdentity Flow

```
┌──────┐         ┌─────────┐         ┌──────────┐
│Client│         │   STS   │         │Keycloak  │
└──┬───┘         └────┬────┘         └────┬─────┘
   │                  │                    │
   │ 1. POST /        │                    │
   │ Action=AssumeRole│                    │
   │ WithWebIdentity  │                    │
   │ WebIdentityToken=│                    │
   │ <JWT>            │                    │
   ├─────────────────>│                    │
   │                  │                    │
   │                  │ 2. Verify JWT      │
   │                  │ - Check signature  │
   │                  │ - Verify issuer    │
   │                  │ - Check expiration │
   │                  ├───────────────────>│
   │                  │                    │
   │                  │ 3. JWKS (public    │
   │                  │    keys)           │
   │                  │<───────────────────┤
   │                  │                    │
   │                  │ 4. Extract claims  │
   │                  │ - username         │
   │                  │ - groups           │
   │                  │ - roles            │
   │                  │                    │
   │                  │ 5. Generate temp   │
   │                  │    credentials     │
   │                  │ - AccessKeyId      │
   │                  │ - SecretAccessKey  │
   │                  │ - SessionToken     │
   │                  │                    │
   │ 6. STS Response  │                    │
   │ <Credentials>    │                    │
   │<─────────────────┤                    │
   │                  │                    │
   └──────────────────┴────────────────────┘
```

### STS Response Format

```xml
<?xml version="1.0" encoding="UTF-8"?>
<AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <AssumeRoleWithWebIdentityResult>
    <Credentials>
      <AccessKeyId>AKIAIOSFODNN7EXAMPLE</AccessKeyId>
      <SecretAccessKey>wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY</SecretAccessKey>
      <SessionToken>AQoDYXdzEJr...EXAMPLETOKEN</SessionToken>
      <Expiration>2024-01-15T12:00:00Z</Expiration>
    </Credentials>
    <AssumedRoleUser>
      <Arn>arn:ozone:iam::assumed-role/admin-role</Arn>
      <AssumedRoleId>AROAI...EXAMPLE</AssumedRoleId>
    </AssumedRoleUser>
  </AssumeRoleWithWebIdentityResult>
</AssumeRoleWithWebIdentityResponse>
```

## Authorization Decision Flow

### S3 Operation Authorization

```
┌──────┐         ┌─────────┐         ┌──────────┐
│Client│         │ S3 API  │         │  Ranger  │
└──┬───┘         └────┬────┘         └────┬─────┘
   │                  │                    │
   │ 1. S3 Request    │                    │
   │ (with session    │                    │
   │  token)          │                    │
   ├─────────────────>│                    │
   │                  │                    │
   │                  │ 2. Validate token  │
   │                  │ - Verify signature │
   │                  │ - Check expiration │
   │                  │ - Extract user info│
   │                  │                    │
   │                  │ 3. Authorization   │
   │                  │    check           │
   │                  │ - Resource: bucket │
   │                  │ - Action: read     │
   │                  │ - User: admin      │
   │                  │ - Groups: [admins] │
   │                  │ - Roles: [s3-admin]│
   │                  ├───────────────────>│
   │                  │                    │
   │                  │ 4. Policy decision │
   │                  │<───────────────────┤
   │                  │                    │
   │                  │ 5. Execute or deny │
   │                  │                    │
   │ 6. Response      │                    │
   │<─────────────────┤                    │
   │                  │                    │
   └──────────────────┴────────────────────┘
```

### Role-to-Permission Mapping

```yaml
Keycloak Role       →  Ozone Permission
─────────────────────────────────────────
s3-admin            →  BUCKET_CREATE
                    →  BUCKET_DELETE
                    →  BUCKET_LIST
                    →  OBJECT_READ
                    →  OBJECT_WRITE
                    →  OBJECT_DELETE

s3-full-access      →  BUCKET_LIST
                    →  OBJECT_READ
                    →  OBJECT_WRITE
                    →  OBJECT_DELETE

s3-read-only        →  BUCKET_LIST
                    →  OBJECT_READ
```

## Component Integration

### STS Service Integration Points

#### 1. IDPAuthenticationProvider

Located: `org.apache.hadoop.ozone.s3.sts.auth.IDPAuthenticationProvider`

```java
public class IDPAuthenticationProvider implements AuthenticationProvider {
  
  @Override
  public AuthenticationResult authenticate(AuthenticationRequest request) {
    // 1. Extract web identity token from request
    String webIdentityToken = request.getWebIdentityToken();
    
    // 2. Parse JWT (decode base64)
    String[] parts = webIdentityToken.split("\\.");
    String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
    JsonNode payload = objectMapper.readTree(payloadJson);
    
    // 3. Validate issuer
    String tokenIssuer = payload.get("iss").asText();
    if (!oidcIssuer.equals(tokenIssuer)) {
      return AuthenticationResult.failure("Invalid issuer");
    }
    
    // 4. Check expiration
    long exp = payload.get("exp").asLong();
    if (System.currentTimeMillis() / 1000 > exp) {
      return AuthenticationResult.failure("Token expired");
    }
    
    // 5. Extract user info and roles
    String subject = payload.get("sub").asText();
    JsonNode groups = payload.get("groups");
    
    // 6. Return authentication result
    return AuthenticationResult.newBuilder()
        .setSuccess(true)
        .setPrincipal(subject)
        .addGroups(groups)
        .build();
  }
}
```

#### 2. STSCredentialGenerator

Located: `org.apache.hadoop.ozone.s3.sts.token.STSCredentialGenerator`

```java
public class STSCredentialGenerator {
  
  public TemporaryCredentials generateCredentials(
      String principal,
      String roleArn,
      String sessionName,
      long durationSeconds,
      Map<String, String> attributes) {
    
    // 1. Generate access key ID
    String accessKeyId = generateAccessKeyId(principal);
    
    // 2. Generate secret access key
    String secretAccessKey = generateSecretAccessKey();
    
    // 3. Create session token (JWT)
    String sessionToken = jwtGenerator.generateToken(
        principal, roleArn, sessionName, durationSeconds, attributes
    );
    
    // 4. Calculate expiration
    Instant expiration = Instant.now().plusSeconds(durationSeconds);
    
    // 5. Store credentials
    TemporaryCredentials credentials = new TemporaryCredentials(
        accessKeyId, secretAccessKey, sessionToken, expiration
    );
    tokenStore.put(accessKeyId, credentials);
    
    return credentials;
  }
}
```

### Configuration Flow

```
docker-config
    │
    ├─> Environment Variables
    │       OZONE-SITE.XML_ozone.s3.sts.enabled=true
    │       OZONE-SITE.XML_ozone.s3.sts.idp.oidc.issuer=...
    │       OZONE-SITE.XML_ozone.s3.sts.idp.oidc.jwks.url=...
    │
    ├─> OzoneConfiguration
    │       │
    │       └─> STSEndpoint.init()
    │               │
    │               ├─> AuthenticationProviderFactory
    │               │       └─> IDPAuthenticationProvider
    │               │
    │               ├─> TokenStore
    │               ├─> JWTTokenGenerator
    │               └─> STSCredentialGenerator
    │
    └─> S3 API Handler
            └─> Uses credentials for authorization
```

## Security Considerations

### 1. Token Security

**OIDC Token (from Keycloak)**:
- Signed with RS256 (RSA + SHA-256)
- Short-lived (5 minutes in demo, configurable)
- Contains user identity and roles
- Verified using JWKS endpoint

**Session Token (from STS)**:
- Signed JWT containing:
  - Original user identity
  - Assumed role
  - Expiration time
  - Additional attributes
- Used for S3 API authentication

### 2. Communication Security

**In Demo (Development)**:
- HTTP (unencrypted)
- Suitable for local testing only

**In Production**:
- HTTPS/TLS for all communication
- Certificate validation
- Secure secret storage (Vault, etc.)

### 3. Credential Lifecycle

```
Token Lifecycle:
  1. User authenticates → OIDC token (5 min)
  2. Exchange for S3 credentials → Session token (1 hour)
  3. Use for S3 operations
  4. Token expires → Re-authenticate or refresh
  5. Credentials automatically revoked on expiration
```

### 4. Authorization Model

**Layered Authorization**:
1. **Token validation**: Is the session token valid?
2. **User extraction**: Who is the user?
3. **Role resolution**: What roles does the user have?
4. **Permission check**: Does the role allow this operation?
5. **Resource ACL**: Does the user have access to this resource?

## Performance and Scalability

### Caching Strategy

```
Token Cache (in-memory):
  - Cache validated OIDC tokens (reduces JWKS lookups)
  - Cache STS credentials (reduces DB lookups)
  - TTL-based expiration
  - LRU eviction policy
  - Default size: 10,000 entries
```

### Scalability Considerations

1. **Horizontal Scaling**:
   - Multiple S3 Gateway instances
   - Shared token cache (Redis/Memcached)
   - Load balancer distribution

2. **Token Store**:
   - In-memory for development
   - Distributed cache for production
   - Database persistence optional

3. **JWKS Caching**:
   - Cache Keycloak's public keys
   - Refresh periodically (e.g., every hour)
   - Fallback to fresh fetch on validation failure

### Performance Metrics

**Expected Latencies** (development environment):
- OIDC token request: 50-100ms
- STS token exchange: 20-50ms
- S3 operation with session token: 10-30ms (+ storage latency)

## Monitoring and Observability

### Key Metrics

```yaml
STS Metrics:
  - sts.token.requests.total: Total token requests
  - sts.token.success: Successful token exchanges
  - sts.token.failures: Failed token exchanges
  - sts.token.cache.hits: Token cache hits
  - sts.token.cache.misses: Token cache misses
  - sts.auth.duration: Authentication latency

S3 API Metrics:
  - s3.requests.total: Total S3 requests
  - s3.requests.authorized: Authorized requests
  - s3.requests.denied: Denied requests
  - s3.session.token.invalid: Invalid session tokens
```

### Logging

```
Log Levels:
  - INFO: Successful authentications, token exchanges
  - WARN: Token expiration, cache evictions
  - ERROR: Authentication failures, invalid tokens
  - DEBUG: Detailed token validation, claim extraction
```

## Deployment Considerations

### Production Checklist

- [ ] Enable HTTPS/TLS for all endpoints
- [ ] Use production-grade secret management
- [ ] Configure appropriate token lifetimes
- [ ] Set up distributed token cache
- [ ] Enable monitoring and alerting
- [ ] Configure backup authentication methods
- [ ] Set up log aggregation
- [ ] Perform security audit
- [ ] Load testing
- [ ] Disaster recovery plan

### Configuration Best Practices

```properties
# Production token lifetimes
ozone.s3.sts.token.default.duration=3600000  # 1 hour
ozone.s3.sts.token.max.lifetime=43200000     # 12 hours
ozone.s3.sts.token.min.duration=900000       # 15 minutes

# Cache configuration
ozone.s3.sts.token.store.cache.size=100000
ozone.s3.sts.token.store.cache.expiry=3600000

# JWKS caching
ozone.s3.sts.idp.jwks.cache.ttl=3600000
ozone.s3.sts.idp.jwks.cache.refresh=900000
```

## Conclusion

This architecture provides:
- **Scalable authentication**: Offloads identity management to Keycloak
- **Flexible authorization**: Role-based access control with group mappings
- **Security**: JWT-based temporary credentials with automatic expiration
- **Compatibility**: AWS STS-compatible API
- **Extensibility**: Pluggable authentication providers

For questions or issues, refer to Apache Ozone documentation or community support channels.

