#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

echo "========================================="
echo "Initializing Keycloak..."
echo "========================================="

KEYCLOAK_URL="http://localhost:8080"
ADMIN_USER="admin"
ADMIN_PASSWORD="admin"
REALM="ozone"

# Wait for Keycloak to be ready
echo "Waiting for Keycloak to start..."
max_attempts=60
attempt=0
until curl -sf "${KEYCLOAK_URL}/health/ready" > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ $attempt -ge $max_attempts ]; then
    echo "ERROR: Keycloak failed to start within expected time"
    exit 1
  fi
  echo "  Attempt $attempt/$max_attempts: Waiting for Keycloak..."
  sleep 5
done

echo "✓ Keycloak is ready!"

# Verify realm was imported
echo ""
echo "Verifying realm configuration..."
if curl -sf "${KEYCLOAK_URL}/realms/${REALM}" > /dev/null 2>&1; then
  echo "✓ Realm '${REALM}' is configured"
else
  echo "ERROR: Realm '${REALM}' not found"
  exit 1
fi

# Display realm information
echo ""
echo "========================================="
echo "Keycloak Configuration Summary"
echo "========================================="
echo "Keycloak URL:    ${KEYCLOAK_URL}"
echo "Admin Console:   ${KEYCLOAK_URL}/admin"
echo "Realm:           ${REALM}"
echo "Client ID:       ozone-s3"
echo ""
echo "Admin Credentials:"
echo "  Username: ${ADMIN_USER}"
echo "  Password: ${ADMIN_PASSWORD}"
echo ""
echo "Test Users:"
echo "  admin      (password: admin123)  - Role: s3-admin, s3-full-access"
echo "  developer  (password: dev123)    - Role: s3-full-access"
echo "  auditor    (password: audit123)  - Role: s3-read-only"
echo ""
echo "OIDC Endpoints:"
echo "  Issuer:     ${KEYCLOAK_URL}/realms/${REALM}"
echo "  Token URL:  ${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token"
echo "  JWKS URL:   ${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/certs"
echo "========================================="
echo ""

# Test token generation for each user
echo "Testing token generation..."
echo ""

for USER in admin developer auditor; do
  case $USER in
    admin)     PASSWORD="admin123" ;;
    developer) PASSWORD="dev123" ;;
    auditor)   PASSWORD="audit123" ;;
  esac
  
  echo "Testing user: $USER"
  
  TOKEN_RESPONSE=$(curl -sf -X POST "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=$USER" \
    -d "password=$PASSWORD" \
    -d "grant_type=password" \
    -d "client_id=ozone-s3" || echo "")
  
  if [ -n "$TOKEN_RESPONSE" ]; then
    ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
    if [ -n "$ACCESS_TOKEN" ]; then
      echo "  ✓ Token generated successfully"
      # Decode token to show claims (base64 decode the payload)
      PAYLOAD=$(echo "$ACCESS_TOKEN" | cut -d'.' -f2)
      # Add padding if needed
      PADDED=$(printf '%s' "$PAYLOAD" | sed 's/-/+/g; s/_/\//g')
      case $((${#PADDED} % 4)) in
        2) PADDED="${PADDED}==" ;;
        3) PADDED="${PADDED}=" ;;
      esac
      echo "  Groups/Roles:"
      echo "$PADDED" | base64 -d 2>/dev/null | grep -o '"groups":\[[^]]*\]' || echo "    (no groups in token)"
    else
      echo "  ✗ Failed to extract access token"
    fi
  else
    echo "  ✗ Failed to get token"
  fi
  echo ""
done

echo "========================================="
echo "Keycloak initialization complete!"
echo "========================================="

