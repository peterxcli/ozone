# AWS STS Service for Ozone S3 Gateway

## Overview

This implementation provides an AWS-compatible Security Token Service (STS) for the Ozone S3 Gateway. It supports multiple authentication backends and issues temporary credentials in both AWS-compatible format and JWT tokens.

## Features

- **Multiple Authentication Backends**:
  - Identity Provider (IDP) - OIDC/SAML support
  - LDAP - Generic LDAP authentication
  - Active Directory (AD) - AD-specific authentication
  - REST API - External authentication service integration

- **AWS STS API Operations**:
  - `AssumeRoleWithWebIdentity` - OIDC token-based authentication
  - `GetCallerIdentity` - Return caller information
  - Future: `AssumeRole`, `AssumeRoleWithSAML`, `GetSessionToken`, `GetFederationToken`

- **Dual Token Format**:
  - AWS-compatible temporary credentials (AccessKeyId, SecretAccessKey, SessionToken)
  - JWT tokens for modern authentication workflows

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      STS Endpoint                            │
│  (POST /  with Action parameter)                             │
└────────────────────┬────────────────────────────────────────┘
                     │
         ┌───────────┴──────────────┐
         │                          │
    ┌────▼─────┐            ┌──────▼──────┐
    │  Action  │            │  Action     │
    │ Handlers │            │  Handlers   │
    └────┬─────┘            └──────┬──────┘
         │                          │
         └───────────┬──────────────┘
                     │
      ┌──────────────▼─────────────────┐
      │ Authentication Provider Factory │
      └──────────────┬─────────────────┘
                     │
         ┌───────────┴──────────────┐
         │                          │
    ┌────▼─────┐            ┌──────▼──────┐
    │   IDP    │            │    LDAP     │
    │ Provider │            │  Provider   │
    └──────────┘            └─────────────┘
    ┌──────────┐            ┌─────────────┐
    │    AD    │            │  REST API   │
    │ Provider │            │  Provider   │
    └──────────┘            └─────────────┘
                     │
      ┌──────────────▼─────────────────┐
      │  Credential & Token Generation  │
      │  - STSCredentialGenerator       │
      │  - JWTTokenGenerator            │
      │  - TokenStore                   │
      └─────────────────────────────────┘
```

## Configuration

### Enable STS Service

```xml
<property>
  <name>ozone.s3.sts.enabled</name>
  <value>true</value>
  <description>Enable or disable the STS service</description>
</property>
```

### Token Lifetime Configuration

```xml
<property>
  <name>ozone.s3.sts.token.max.lifetime</name>
  <value>43200000</value> <!-- 12 hours in milliseconds -->
  <description>Maximum token lifetime</description>
</property>

<property>
  <name>ozone.s3.sts.token.default.duration</name>
  <value>3600000</value> <!-- 1 hour in milliseconds -->
  <description>Default token duration</description>
</property>

<property>
  <name>ozone.s3.sts.token.min.duration</name>
  <value>900000</value> <!-- 15 minutes in milliseconds -->
  <description>Minimum token duration</description>
</property>
```

### JWT Configuration

```xml
<property>
  <name>ozone.s3.sts.jwt.secret</name>
  <value>your-secret-key-change-in-production</value>
  <description>Secret key for JWT token signing</description>
</property>

<property>
  <name>ozone.s3.sts.jwt.issuer</name>
  <value>ozone-sts</value>
  <description>JWT token issuer</description>
</property>

<property>
  <name>ozone.s3.sts.jwt.audience</name>
  <value>s3-gateway</value>
  <description>JWT token audience</description>
</property>
```

### Authentication Providers

```xml
<property>
  <name>ozone.s3.sts.auth.providers</name>
  <value>IDP,LDAP,AD,REST_API</value>
  <description>Comma-separated list of enabled authentication providers</description>
</property>
```

### IDP Provider Configuration

```xml
<!-- OIDC Configuration -->
<property>
  <name>ozone.s3.sts.idp.oidc.issuer</name>
  <value>https://accounts.google.com</value>
  <description>OIDC issuer URL</description>
</property>

<property>
  <name>ozone.s3.sts.idp.oidc.jwks.url</name>
  <value>https://www.googleapis.com/oauth2/v3/certs</value>
  <description>OIDC JWKS URL for token verification</description>
</property>

<property>
  <name>ozone.s3.sts.idp.oidc.client.id</name>
  <value>your-client-id</value>
  <description>OIDC client ID</description>
</property>

<!-- SAML Configuration -->
<property>
  <name>ozone.s3.sts.idp.saml.metadata.url</name>
  <value>https://idp.example.com/metadata</value>
  <description>SAML metadata URL</description>
</property>

<property>
  <name>ozone.s3.sts.idp.saml.entity.id</name>
  <value>https://ozone.example.com/sp</value>
  <description>SAML entity ID</description>
</property>
```

### LDAP Provider Configuration

```xml
<property>
  <name>ozone.s3.sts.ldap.url</name>
  <value>ldap://ldap.example.com:389</value>
  <description>LDAP server URL</description>
</property>

<property>
  <name>ozone.s3.sts.ldap.base.dn</name>
  <value>dc=example,dc=com</value>
  <description>LDAP base DN</description>
</property>

<property>
  <name>ozone.s3.sts.ldap.user.dn.pattern</name>
  <value>uid={0},ou=users</value>
  <description>User DN pattern</description>
</property>

<property>
  <name>ozone.s3.sts.ldap.group.search.base</name>
  <value>ou=groups,dc=example,dc=com</value>
  <description>Group search base DN</description>
</property>
```

### Active Directory Provider Configuration

```xml
<property>
  <name>ozone.s3.sts.ad.url</name>
  <value>ldaps://ad.example.com:636</value>
  <description>Active Directory server URL</description>
</property>

<property>
  <name>ozone.s3.sts.ad.domain</name>
  <value>example.com</value>
  <description>Active Directory domain</description>
</property>

<property>
  <name>ozone.s3.sts.ad.search.base</name>
  <value>dc=example,dc=com</value>
  <description>AD search base DN</description>
</property>
```

### REST API Provider Configuration

```xml
<property>
  <name>ozone.s3.sts.rest.auth.url</name>
  <value>https://auth.example.com/api/validate</value>
  <description>REST API authentication endpoint</description>
</property>

<property>
  <name>ozone.s3.sts.rest.auth.method</name>
  <value>POST</value>
  <description>HTTP method for authentication</description>
</property>

<property>
  <name>ozone.s3.sts.rest.auth.timeout</name>
  <value>5000</value>
  <description>Request timeout in milliseconds</description>
</property>

<property>
  <name>ozone.s3.sts.rest.auth.headers</name>
  <value>Content-Type:application/json,X-API-Key:your-api-key</value>
  <description>Custom headers for REST API calls</description>
</property>
```

## Usage Examples

### AssumeRoleWithWebIdentity

#### Request

```bash
curl -X POST https://s3gateway.example.com/ \
  -d "Action=AssumeRoleWithWebIdentity" \
  -d "RoleArn=arn:ozone:iam::role/data-scientist" \
  -d "RoleSessionName=user-session-1" \
  -d "WebIdentityToken=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d "DurationSeconds=3600" \
  -d "Version=2011-06-15"
```

#### Response

```xml
<AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <AssumeRoleResult>
    <Credentials>
      <AccessKeyId>STS-A1B2C3D4E5F6G7H8</AccessKeyId>
      <SecretAccessKey>wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY</SecretAccessKey>
      <SessionToken>eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...</SessionToken>
      <Expiration>2025-10-15T12:00:00Z</Expiration>
    </Credentials>
    <AssumedRoleUser>
      <AssumedRoleId>STS-A1B2C3D4E5F6G7H8</AssumedRoleId>
      <Arn>arn:ozone:iam::role/data-scientist</Arn>
    </AssumedRoleUser>
    <JWTToken>eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...</JWTToken>
  </AssumeRoleResult>
  <ResponseMetadata>
    <RequestId>b3f8c1a2-d4e5-6f7g-8h9i-0j1k2l3m4n5o</RequestId>
  </ResponseMetadata>
</AssumeRoleResponse>
```

### GetCallerIdentity

#### Request

```bash
curl -X POST https://s3gateway.example.com/ \
  -d "Action=GetCallerIdentity" \
  -d "Version=2011-06-15"
```

#### Response

```xml
<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
  <GetCallerIdentityResult>
    <Arn>arn:ozone:iam::user/john.doe</Arn>
    <UserId>john.doe</UserId>
    <Account>ozone</Account>
  </GetCallerIdentityResult>
  <ResponseMetadata>
    <RequestId>a1b2c3d4-e5f6-g7h8-i9j0-k1l2m3n4o5p6</RequestId>
  </ResponseMetadata>
</GetCallerIdentityResponse>
```

### Using Temporary Credentials with AWS CLI

```bash
# Set temporary credentials
export AWS_ACCESS_KEY_ID=STS-A1B2C3D4E5F6G7H8
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_SESSION_TOKEN=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# Use with AWS CLI
aws s3 ls s3://mybucket --endpoint-url https://s3gateway.example.com
```

## Token Formats

### AWS-Compatible Credentials

```
AccessKeyId: STS-{16-char-uuid}
SecretAccessKey: {40-char-hex-string}
SessionToken: {JWT-token}
```

### JWT Token Claims

```json
{
  "sub": "user@domain.com",
  "iss": "ozone-sts",
  "aud": "s3-gateway",
  "exp": 1729000000,
  "iat": 1728996400,
  "role": "arn:ozone:iam::role/data-scientist",
  "sessionName": "user-session-1",
  "email": "user@domain.com",
  "groups": ["engineers", "data-team"]
}
```

## Security Considerations

1. **JWT Secret**: Change the default JWT secret in production. Use a strong, random secret key.

2. **HTTPS**: Always use HTTPS in production to protect credentials in transit.

3. **Token Expiration**: Configure appropriate token lifetimes based on your security requirements.

4. **Provider Security**:
   - Use LDAPS (LDAP over SSL) for LDAP/AD connections
   - Validate SSL certificates for REST API calls
   - Implement proper OIDC token verification with JWKS

5. **Rate Limiting**: Consider implementing rate limiting on the STS endpoint to prevent abuse.

## Troubleshooting

### Enable Debug Logging

```xml
<property>
  <name>log4j.logger.org.apache.hadoop.ozone.s3.sts</name>
  <value>DEBUG</value>
</property>
```

### Common Issues

1. **Token Validation Fails**: Check JWT secret configuration and clock synchronization

2. **Authentication Provider Not Found**: Verify provider is listed in `ozone.s3.sts.auth.providers`

3. **LDAP Connection Errors**: Check LDAP URL, credentials, and network connectivity

4. **OIDC Token Rejected**: Verify issuer, audience, and token expiration

## Testing

### Unit Tests

```bash
mvn test -Dtest=TestJWTTokenGenerator
mvn test -Dtest=TestTokenStore
mvn test -Dtest=TestTokenValidator
```

### Integration Tests

```bash
mvn verify -Dtest=TestSTSEndpoint
```

## Future Enhancements

- Complete SAML assertion validation
- Additional STS operations (AssumeRole, GetSessionToken, GetFederationToken)
- Token revocation support
- Multi-factor authentication (MFA)
- Fine-grained session policies
- Integration with Apache Ranger for authorization
- Persistent token storage (database backend)
- Token refresh mechanism

## References

- [AWS STS API Reference](https://docs.aws.amazon.com/STS/latest/APIReference/)
- [OIDC Specification](https://openid.net/specs/openid-connect-core-1_0.html)
- [JWT RFC 7519](https://tools.ietf.org/html/rfc7519)
- [SAML 2.0 Specification](https://docs.oasis-open.org/security/saml/v2.0/)

