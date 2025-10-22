# Ozone STS + Keycloak Demo Walkthrough

This document provides a detailed step-by-step walkthrough of the Ozone STS with Keycloak integration demo.

## Table of Contents

- [Setup](#setup)
- [User Personas](#user-personas)
- [Scenario 1: Admin Full Access Workflow](#scenario-1-admin-full-access-workflow)
- [Scenario 2: Developer Read/Write Access](#scenario-2-developer-readwrite-access)
- [Scenario 3: Auditor Read-Only Access](#scenario-3-auditor-read-only-access)
- [Scenario 4: Token Expiration and Refresh](#scenario-4-token-expiration-and-refresh)
- [Scenario 5: Access Denial Demonstrations](#scenario-5-access-denial-demonstrations)

## Setup

### Start the Environment

```bash
# Navigate to the demo directory
cd hadoop-ozone/dist/src/main/compose/ozone-sts-keycloak

# Start all services
docker-compose up -d

# Monitor startup (wait for all services to be healthy)
watch docker-compose ps
```

Expected output after ~60 seconds:
```
NAME        IMAGE                          STATUS
postgres    postgres:15-alpine            Up (healthy)
keycloak    quay.io/keycloak/keycloak:23  Up (healthy)
scm         apache/ozone-runner:latest    Up
om          apache/ozone-runner:latest    Up
datanode    apache/ozone-runner:latest    Up
s3g         apache/ozone-runner:latest    Up
recon       apache/ozone-runner:latest    Up
```

### Initialize Keycloak

```bash
./init-keycloak.sh
```

**Expected Output:**
```
=========================================
Initializing Keycloak...
=========================================
Waiting for Keycloak to start...
✓ Keycloak is ready!

Verifying realm configuration...
✓ Realm 'ozone' is configured

=========================================
Keycloak Configuration Summary
=========================================
Keycloak URL:    http://localhost:8080
Admin Console:   http://localhost:8080/admin
Realm:           ozone
Client ID:       ozone-s3

Admin Credentials:
  Username: admin
  Password: admin

Test Users:
  admin      (password: admin123)  - Role: s3-admin, s3-full-access
  developer  (password: dev123)    - Role: s3-full-access
  auditor    (password: audit123)  - Role: s3-read-only

OIDC Endpoints:
  Issuer:     http://localhost:8080/realms/ozone
  Token URL:  http://localhost:8080/realms/ozone/protocol/openid-connect/token
  JWKS URL:   http://localhost:8080/realms/ozone/protocol/openid-connect/certs
=========================================
```

### Initialize Ozone

```bash
./init-ozone.sh
```

**Expected Output:**
```
=========================================
Initializing Ozone cluster...
=========================================
Waiting for Ozone Manager...
✓ Ozone Manager is ready

Waiting for S3 Gateway...
✓ S3 Gateway is ready

Verifying STS service...
✓ STS service is enabled and running

Creating test volumes and buckets...
✓ Test buckets created:
  - admin-bucket (for admin user)
  - dev-bucket (for developer user)
  - shared-bucket (for all users)

=========================================
Ozone Configuration Summary
=========================================
Ozone Manager:   http://localhost:9874
S3 Gateway:      http://localhost:9878
Recon:           http://localhost:9888
STS Endpoint:    http://localhost:9878/
=========================================
```

## User Personas

### Admin User
- **Username**: admin
- **Password**: admin123
- **Roles**: s3-admin, s3-full-access
- **Permissions**:
  - ✓ Create buckets
  - ✓ Delete buckets
  - ✓ Upload objects
  - ✓ Download objects
  - ✓ Delete objects
  - ✓ List all resources

### Developer User
- **Username**: developer
- **Password**: dev123
- **Roles**: s3-full-access
- **Permissions**:
  - ✗ Create buckets
  - ✗ Delete buckets
  - ✓ Upload objects
  - ✓ Download objects
  - ✓ Delete objects
  - ✓ List objects

### Auditor User
- **Username**: auditor
- **Password**: audit123
- **Roles**: s3-read-only
- **Permissions**:
  - ✗ Create buckets
  - ✗ Delete buckets
  - ✗ Upload objects
  - ✓ Download objects
  - ✗ Delete objects
  - ✓ List objects

## Scenario 1: Admin Full Access Workflow

This scenario demonstrates the complete workflow for an admin user with full S3 access.

### Step 1: Authenticate with Keycloak

```bash
# Get OIDC access token
ADMIN_TOKEN_RESPONSE=$(curl -sf -X POST \
  http://localhost:8080/realms/ozone/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin" \
  -d "password=admin123" \
  -d "grant_type=password" \
  -d "client_id=ozone-s3")

# Extract access token
ADMIN_ACCESS_TOKEN=$(echo $ADMIN_TOKEN_RESPONSE | jq -r '.access_token')

echo "Admin OIDC Token: ${ADMIN_ACCESS_TOKEN:0:50}..."
```

**Expected Output:**
```
Admin OIDC Token: eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA...
```

### Step 2: Decode and Inspect Token

```bash
# Decode JWT payload (base64)
PAYLOAD=$(echo $ADMIN_ACCESS_TOKEN | cut -d'.' -f2)
echo $PAYLOAD | base64 -d | jq .
```

**Expected Output:**
```json
{
  "exp": 1234567890,
  "iat": 1234567590,
  "jti": "uuid-here",
  "iss": "http://localhost:8080/realms/ozone",
  "aud": "ozone-s3",
  "sub": "uuid-here",
  "typ": "Bearer",
  "azp": "ozone-s3",
  "preferred_username": "admin",
  "email": "admin@ozone.example.com",
  "groups": [
    "admins"
  ],
  "roles": [
    "s3-admin",
    "s3-full-access"
  ]
}
```

### Step 3: Exchange Token for S3 Credentials

```bash
# Call STS AssumeRoleWithWebIdentity
STS_RESPONSE=$(curl -sf -X POST http://localhost:9878/ \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "Action=AssumeRoleWithWebIdentity" \
  -d "Version=2011-06-15" \
  -d "WebIdentityToken=$ADMIN_ACCESS_TOKEN" \
  -d "RoleArn=arn:ozone:iam::admin-role" \
  -d "RoleSessionName=admin-session-$(date +%s)" \
  -d "DurationSeconds=3600")

# Extract credentials
export AWS_ACCESS_KEY_ID=$(echo $STS_RESPONSE | grep -o '<AccessKeyId>[^<]*' | sed 's/<AccessKeyId>//')
export AWS_SECRET_ACCESS_KEY=$(echo $STS_RESPONSE | grep -o '<SecretAccessKey>[^<]*' | sed 's/<SecretAccessKey>//')
export AWS_SESSION_TOKEN=$(echo $STS_RESPONSE | grep -o '<SessionToken>[^<]*' | sed 's/<SessionToken>//')

echo "AWS_ACCESS_KEY_ID: $AWS_ACCESS_KEY_ID"
echo "AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY:0:20}..."
echo "AWS_SESSION_TOKEN: ${AWS_SESSION_TOKEN:0:50}..."
```

**Expected Output:**
```
AWS_ACCESS_KEY_ID: AKIAIOSFODNN7EXAMPLE
AWS_SECRET_ACCESS_KEY: wJalrXUtnFEMI/K7MDENG...
AWS_SESSION_TOKEN: AQoDYXdzEJr...MqLGKlMo3NzBWkp8hKvJDw3WNsE...
```

### Step 4: Perform S3 Operations

```bash
# List buckets
aws s3 ls --endpoint-url http://localhost:9878
```

**Expected Output:**
```
2024-01-15 10:00:00 admin-bucket
2024-01-15 10:00:00 dev-bucket
2024-01-15 10:00:00 shared-bucket
```

```bash
# Create a new bucket
aws s3 mb s3://admin-new-bucket --endpoint-url http://localhost:9878
```

**Expected Output:**
```
make_bucket: admin-new-bucket
```

```bash
# Upload an object
echo "Admin test data" > /tmp/admin-test.txt
aws s3 cp /tmp/admin-test.txt s3://shared-bucket/admin-test.txt \
  --endpoint-url http://localhost:9878
```

**Expected Output:**
```
upload: /tmp/admin-test.txt to s3://shared-bucket/admin-test.txt
```

```bash
# Download the object
aws s3 cp s3://shared-bucket/admin-test.txt /tmp/admin-download.txt \
  --endpoint-url http://localhost:9878
cat /tmp/admin-download.txt
```

**Expected Output:**
```
download: s3://shared-bucket/admin-test.txt to /tmp/admin-download.txt
Admin test data
```

```bash
# Delete the object
aws s3 rm s3://shared-bucket/admin-test.txt \
  --endpoint-url http://localhost:9878
```

**Expected Output:**
```
delete: s3://shared-bucket/admin-test.txt
```

```bash
# Delete the bucket
aws s3 rb s3://admin-new-bucket --endpoint-url http://localhost:9878
```

**Expected Output:**
```
remove_bucket: admin-new-bucket
```

## Scenario 2: Developer Read/Write Access

This scenario shows a developer with read/write access but no bucket management permissions.

### Step 1-2: Authenticate and Get Token

```bash
# Get OIDC token for developer
DEV_TOKEN_RESPONSE=$(curl -sf -X POST \
  http://localhost:8080/realms/ozone/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=developer" \
  -d "password=dev123" \
  -d "grant_type=password" \
  -d "client_id=ozone-s3")

DEV_ACCESS_TOKEN=$(echo $DEV_TOKEN_RESPONSE | jq -r '.access_token')
```

### Step 3: Get S3 Credentials

```bash
# Call STS
STS_RESPONSE=$(curl -sf -X POST http://localhost:9878/ \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "Action=AssumeRoleWithWebIdentity" \
  -d "Version=2011-06-15" \
  -d "WebIdentityToken=$DEV_ACCESS_TOKEN" \
  -d "RoleArn=arn:ozone:iam::developer-role" \
  -d "RoleSessionName=dev-session-$(date +%s)" \
  -d "DurationSeconds=3600")

# Export credentials
export AWS_ACCESS_KEY_ID=$(echo $STS_RESPONSE | grep -o '<AccessKeyId>[^<]*' | sed 's/<AccessKeyId>//')
export AWS_SECRET_ACCESS_KEY=$(echo $STS_RESPONSE | grep -o '<SecretAccessKey>[^<]*' | sed 's/<SecretAccessKey>//')
export AWS_SESSION_TOKEN=$(echo $STS_RESPONSE | grep -o '<SessionToken>[^<]*' | sed 's/<SessionToken>//')
```

### Step 4: Test Permissions

```bash
# ✓ Can upload objects
echo "Developer data" > /tmp/dev-test.txt
aws s3 cp /tmp/dev-test.txt s3://shared-bucket/dev-test.txt \
  --endpoint-url http://localhost:9878
```

**Expected Output:**
```
upload: /tmp/dev-test.txt to s3://shared-bucket/dev-test.txt
```

```bash
# ✓ Can download objects
aws s3 cp s3://shared-bucket/dev-test.txt /tmp/dev-download.txt \
  --endpoint-url http://localhost:9878
```

**Expected Output:**
```
download: s3://shared-bucket/dev-test.txt to /tmp/dev-download.txt
```

```bash
# ✗ Cannot create buckets
aws s3 mb s3://dev-new-bucket --endpoint-url http://localhost:9878
```

**Expected Output:**
```
make_bucket failed: s3://dev-new-bucket An error occurred (AccessDenied) when calling the CreateBucket operation: Access Denied
```

## Scenario 3: Auditor Read-Only Access

This scenario demonstrates read-only access for an auditor.

### Steps 1-3: Authenticate and Get Credentials

```bash
# Get OIDC token
AUDITOR_TOKEN_RESPONSE=$(curl -sf -X POST \
  http://localhost:8080/realms/ozone/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=auditor" \
  -d "password=audit123" \
  -d "grant_type=password" \
  -d "client_id=ozone-s3")

AUDITOR_ACCESS_TOKEN=$(echo $AUDITOR_TOKEN_RESPONSE | jq -r '.access_token')

# Get S3 credentials from STS
STS_RESPONSE=$(curl -sf -X POST http://localhost:9878/ \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "Action=AssumeRoleWithWebIdentity" \
  -d "Version=2011-06-15" \
  -d "WebIdentityToken=$AUDITOR_ACCESS_TOKEN" \
  -d "RoleArn=arn:ozone:iam::auditor-role" \
  -d "RoleSessionName=auditor-session-$(date +%s)")

export AWS_ACCESS_KEY_ID=$(echo $STS_RESPONSE | grep -o '<AccessKeyId>[^<]*' | sed 's/<AccessKeyId>//')
export AWS_SECRET_ACCESS_KEY=$(echo $STS_RESPONSE | grep -o '<SecretAccessKey>[^<]*' | sed 's/<SecretAccessKey>//')
export AWS_SESSION_TOKEN=$(echo $STS_RESPONSE | grep -o '<SessionToken>[^<]*' | sed 's/<SessionToken>//')
```

### Step 4: Test Read-Only Permissions

```bash
# ✓ Can list buckets
aws s3 ls --endpoint-url http://localhost:9878
```

**Expected Output:**
```
2024-01-15 10:00:00 admin-bucket
2024-01-15 10:00:00 dev-bucket
2024-01-15 10:00:00 shared-bucket
```

```bash
# ✓ Can download objects
aws s3 cp s3://shared-bucket/dev-test.txt /tmp/auditor-download.txt \
  --endpoint-url http://localhost:9878
```

**Expected Output:**
```
download: s3://shared-bucket/dev-test.txt to /tmp/auditor-download.txt
```

```bash
# ✗ Cannot upload objects
echo "Auditor test" > /tmp/auditor-test.txt
aws s3 cp /tmp/auditor-test.txt s3://shared-bucket/auditor-test.txt \
  --endpoint-url http://localhost:9878
```

**Expected Output:**
```
upload failed: /tmp/auditor-test.txt to s3://shared-bucket/auditor-test.txt An error occurred (AccessDenied) when calling the PutObject operation: Access Denied
```

## Scenario 4: Token Expiration and Refresh

This scenario demonstrates token lifecycle management.

### Check Token Expiration

```bash
# Decode token to see expiration
PAYLOAD=$(echo $ADMIN_ACCESS_TOKEN | cut -d'.' -f2)
EXP=$(echo $PAYLOAD | base64 -d | jq -r '.exp')
NOW=$(date +%s)
REMAINING=$((EXP - NOW))

echo "Token expires in: $REMAINING seconds"
```

### Refresh Token

```bash
# Use refresh token to get new access token
REFRESH_TOKEN=$(echo $ADMIN_TOKEN_RESPONSE | jq -r '.refresh_token')

NEW_TOKEN_RESPONSE=$(curl -sf -X POST \
  http://localhost:8080/realms/ozone/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=refresh_token" \
  -d "client_id=ozone-s3" \
  -d "refresh_token=$REFRESH_TOKEN")

NEW_ACCESS_TOKEN=$(echo $NEW_TOKEN_RESPONSE | jq -r '.access_token')

echo "New token obtained: ${NEW_ACCESS_TOKEN:0:50}..."
```

## Scenario 5: Access Denial Demonstrations

### Developer Trying to Create Bucket

```bash
# This will fail
aws s3 mb s3://unauthorized-bucket --endpoint-url http://localhost:9878 2>&1
```

**Expected Error:**
```
make_bucket failed: s3://unauthorized-bucket An error occurred (AccessDenied)
```

### Auditor Trying to Delete Object

```bash
# This will fail
aws s3 rm s3://shared-bucket/test.txt --endpoint-url http://localhost:9878 2>&1
```

**Expected Error:**
```
delete failed: s3://shared-bucket/test.txt An error occurred (AccessDenied)
```

## Summary

This demo has shown:
1. ✓ OIDC authentication with Keycloak
2. ✓ Token exchange via STS AssumeRoleWithWebIdentity
3. ✓ Role-based access control enforcement
4. ✓ Temporary credential usage for S3 operations
5. ✓ Token lifecycle management (expiration and refresh)

## Next Steps

- Explore Python demos for programmatic access
- Customize roles and permissions in Keycloak
- Integrate with existing identity providers
- Set up production-grade security (HTTPS, secrets management)

