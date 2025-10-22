# Ozone STS with Keycloak Integration Demo

This directory contains a complete demonstration of Apache Ozone's Security Token Service (STS) integrated with Keycloak as an OIDC (OpenID Connect) identity provider.

## Overview

This demo showcases:
- **OIDC Authentication**: Users authenticate with Keycloak to obtain JWT tokens
- **Token Exchange**: OIDC tokens are exchanged for temporary S3 credentials via STS
- **Role-Based Access Control**: Different user roles have different S3 permissions
- **Temporary Credentials**: Short-lived S3 access keys with automatic expiration
- **Complete Workflow**: End-to-end demo from authentication to S3 operations

## Architecture

```
┌─────────┐         ┌──────────┐         ┌────────┐         ┌─────────┐
│  User   │────1───>│ Keycloak │────2───>│  STS   │────3───>│   S3    │
└─────────┘         └──────────┘         └────────┘         └─────────┘
                         │                    │                   │
                    OIDC Token          Temporary            S3 API
                    (JWT)               Credentials          Operations
```

1. User authenticates with Keycloak → receives OIDC access token (JWT)
2. User calls STS with OIDC token → receives temporary S3 credentials
3. User performs S3 operations using temporary credentials

## Prerequisites

- **Docker** and **Docker Compose** (Docker daemon must be running)
- **Python 3.8+** (for Python demos)
- **AWS CLI** (for shell demos - install via `pip install awscli`)
- **curl** and **jq** (for shell demos)
- At least **8GB RAM** available
- Ports available: 8080, 9874, 9876, 9878, 9888

### Install Prerequisites

```bash
# macOS
brew install awscli jq

# Ubuntu/Debian
sudo apt-get install awscli jq

# Python tools
pip install awscli
```

## Quick Start

### 1. Start the Environment

```bash
# Ensure Docker daemon is running
docker ps

# From this directory
docker-compose up -d

# Wait for services to be ready (takes ~60-90 seconds)
docker-compose ps
```

Expected output after services are ready:
```
NAME        STATUS
postgres    Up (healthy)
keycloak    Up (healthy)
scm         Up
om          Up
datanode    Up
s3g         Up
recon       Up
```

### 2. Initialize Services

```bash
# Initialize Keycloak (verify realm and users)
./init-keycloak.sh

# Initialize Ozone (create test buckets)
./init-ozone.sh
```

### 3. Run Demo

#### Option A: Shell Demo

```bash
cd demos/shell

# Step 1: Get OIDC tokens from Keycloak
./01-authenticate-and-get-token.sh

# Step 2: Exchange tokens for S3 credentials
./02-assume-role-with-web-identity.sh

# Step 3: Use S3 credentials
./03-use-s3-credentials.sh

# Step 4: Role-based access demo
./04-role-based-access-demo.sh
```

#### Option B: Python Demo

```bash
cd demos/python

# Install dependencies
pip install -r requirements.txt

# Run complete workflow demo
python demo_full_workflow.py

# Run token lifecycle demo
python demo_token_lifecycle.py
```

## Components

### Services

- **Keycloak** (port 8080): OIDC identity provider
- **PostgreSQL** (internal): Keycloak database
- **Ozone SCM** (port 9876): Storage Container Manager
- **Ozone OM** (port 9874): Ozone Manager
- **Ozone Datanode** (port 19864): Data storage
- **S3 Gateway** (port 9878): S3-compatible API with STS enabled
- **Recon** (port 9888): Ozone monitoring and management

### Users and Roles

Three pre-configured users with different permission levels:

| Username  | Password  | Role            | Permissions                                    |
|-----------|-----------|-----------------|------------------------------------------------|
| admin     | admin123  | s3-admin        | Full access: create/delete buckets, read/write |
| developer | dev123    | s3-full-access  | Read/write objects (cannot manage buckets)     |
| auditor   | audit123  | s3-read-only    | Read-only access to objects                    |

### Configuration

Key configuration in `docker-config`:

```properties
# Enable STS
OZONE-SITE.XML_ozone.s3.sts.enabled=true
OZONE-SITE.XML_ozone.s3.sts.auth.providers=IDP

# Keycloak OIDC configuration
OZONE-SITE.XML_ozone.s3.sts.idp.oidc.issuer=http://keycloak:8080/realms/ozone
OZONE-SITE.XML_ozone.s3.sts.idp.oidc.jwks.url=http://keycloak:8080/realms/ozone/protocol/openid-connect/certs
OZONE-SITE.XML_ozone.s3.sts.idp.oidc.client.id=ozone-s3

# Token lifetime (5 minutes for demo)
OZONE-SITE.XML_ozone.s3.sts.token.default.duration=300000
```

## Endpoints

- **Keycloak Admin Console**: http://localhost:8080/admin
  - Username: `admin`
  - Password: `admin`

- **Keycloak Realm**: http://localhost:8080/realms/ozone

- **STS Endpoint**: http://localhost:9878/

- **S3 Gateway**: http://localhost:9878/

- **Ozone Manager UI**: http://localhost:9874/

- **Recon UI**: http://localhost:9888/

## API Examples

### Get OIDC Token

```bash
curl -X POST http://localhost:8080/realms/ozone/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin" \
  -d "password=admin123" \
  -d "grant_type=password" \
  -d "client_id=ozone-s3"
```

### AssumeRoleWithWebIdentity

```bash
curl -X POST http://localhost:9878/ \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "Action=AssumeRoleWithWebIdentity" \
  -d "Version=2011-06-15" \
  -d "WebIdentityToken=${OIDC_TOKEN}" \
  -d "RoleArn=arn:ozone:iam::admin-role" \
  -d "RoleSessionName=my-session" \
  -d "DurationSeconds=3600"
```

### Use S3 Credentials

```bash
export AWS_ACCESS_KEY_ID=<access_key_id>
export AWS_SECRET_ACCESS_KEY=<secret_access_key>
export AWS_SESSION_TOKEN=<session_token>

aws s3 ls --endpoint-url http://localhost:9878
```

## Troubleshooting

### Docker Daemon Not Running

```bash
# Check if Docker is running
docker ps

# If not, start Docker Desktop or Docker daemon
# macOS: Start Docker Desktop application
# Linux: sudo systemctl start docker
```

### Services Not Starting

```bash
# Check service status
docker-compose ps

# View logs
docker-compose logs keycloak
docker-compose logs s3g

# Restart services
docker-compose restart

# Full restart
docker-compose down && docker-compose up -d
```

### Keycloak Not Ready

```bash
# Check Keycloak health
curl http://localhost:8080/health/ready

# Wait for realm to be imported
docker-compose logs keycloak | grep "realm.*imported"

# Restart Keycloak
docker-compose restart keycloak
```

### STS Not Enabled

```bash
# Check STS configuration
docker exec s3g printenv | grep STS

# Check STS health
curl http://localhost:9878/health

# Check S3Gateway logs
docker-compose logs s3g | tail -100
```

### Authentication Failures

```bash
# Verify user in Keycloak admin console
open http://localhost:8080/admin

# Check Keycloak logs
docker-compose logs keycloak | grep ERROR

# Test token generation manually
curl -X POST http://localhost:8080/realms/ozone/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin" \
  -d "password=admin123" \
  -d "grant_type=password" \
  -d "client_id=ozone-s3" | jq
```

### S3 Operation Failures

```bash
# Check S3 Gateway logs
docker-compose logs s3g | tail -50

# Verify buckets exist
docker exec om ozone sh bucket list /s3v/

# Test S3 endpoint
curl http://localhost:9878/
```

### Port Already in Use

```bash
# Find process using port
lsof -i :8080  # or :9878, :9874, etc.

# Kill the process
kill -9 <PID>

# Or change ports in docker-compose.yaml
```

## Cleanup

```bash
# Stop all services
docker-compose down

# Remove volumes (delete all data)
docker-compose down -v

# Remove generated demo files
cd demos/shell
rm -f .*.json .*.sh .*.txt
```

## Advanced Topics

### Custom Roles

Edit `keycloak-realm.json` to add custom roles:

```json
{
  "name": "custom-role",
  "description": "Custom S3 role",
  "composite": false,
  "clientRole": false
}
```

### Token Lifetime

Modify token duration in `docker-config`:

```properties
# Set to 1 hour (in milliseconds)
OZONE-SITE.XML_ozone.s3.sts.token.default.duration=3600000
```

### Additional Authentication Providers

STS supports multiple auth providers (see `STSConfigKeys.java`):
- IDP (OIDC/SAML)
- LDAP
- Active Directory
- REST API
- Kerberos

### Production Deployment

For production use, consider:
- **TLS/HTTPS**: Enable SSL for all endpoints
- **External Keycloak**: Use existing Keycloak instance
- **Secret Management**: Use Vault or similar for secrets
- **High Availability**: Deploy multiple S3 Gateway instances
- **Monitoring**: Set up metrics and alerting
- **Longer Token Lifetimes**: Adjust based on security requirements

## Documentation

- **[QUICKSTART.md](QUICKSTART.md)**: Get started in under 5 minutes
- **[DEMO.md](DEMO.md)**: Detailed step-by-step demo walkthrough
- **[ARCHITECTURE.md](ARCHITECTURE.md)**: Technical architecture deep dive
- **[IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)**: Implementation overview

## Running Tests

```bash
# Run automated integration tests
./test-integration.sh
```

Expected output:
```
[PASS] All services are running
[PASS] Keycloak realm 'ozone' is configured
[PASS] OIDC token generation working for all users
[PASS] STS service is healthy
[PASS] STS AssumeRoleWithWebIdentity successful
[PASS] S3 operations with temporary credentials working
[PASS] RBAC working: auditor cannot create buckets

Total tests:  7
Passed:       7
Failed:       0

All tests passed!
```

## Support

For issues or questions:
- **Apache Ozone JIRA**: https://issues.apache.org/jira/browse/HDDS
- **Dev mailing list**: dev@ozone.apache.org
- **User mailing list**: user@ozone.apache.org
- **Documentation**: https://ozone.apache.org

## License

Licensed under the Apache License, Version 2.0. See LICENSE.txt in the repository root.

