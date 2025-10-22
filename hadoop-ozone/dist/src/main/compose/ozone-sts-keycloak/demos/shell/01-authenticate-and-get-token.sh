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

# Demo: Authenticate with Keycloak and get OIDC token

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="${REALM:-ozone}"

echo "========================================="
echo "Demo 1: Authenticate and Get OIDC Token"
echo "========================================="
echo ""

# Function to decode JWT payload
decode_jwt_payload() {
  local token=$1
  local payload=$(echo "$token" | cut -d'.' -f2)
  # Add padding if needed
  local padded=$(printf '%s' "$payload" | sed 's/-/+/g; s/_/\//g')
  case $((${#padded} % 4)) in
    2) padded="${padded}==" ;;
    3) padded="${padded}=" ;;
  esac
  echo "$padded" | base64 -d 2>/dev/null
}

# Function to authenticate and get token
get_oidc_token() {
  local username=$1
  local password=$2
  
  echo "Authenticating as user: $username"
  echo "Keycloak URL: ${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token"
  echo ""
  
  # Get token from Keycloak
  TOKEN_RESPONSE=$(curl -sf -X POST \
    "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=$username" \
    -d "password=$password" \
    -d "grant_type=password" \
    -d "client_id=ozone-s3")
  
  if [ $? -ne 0 ] || [ -z "$TOKEN_RESPONSE" ]; then
    echo "ERROR: Failed to get token from Keycloak"
    return 1
  fi
  
  # Extract tokens
  ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
  REFRESH_TOKEN=$(echo "$TOKEN_RESPONSE" | grep -o '"refresh_token":"[^"]*' | cut -d'"' -f4)
  EXPIRES_IN=$(echo "$TOKEN_RESPONSE" | grep -o '"expires_in":[0-9]*' | cut -d':' -f2)
  
  if [ -z "$ACCESS_TOKEN" ]; then
    echo "ERROR: Failed to extract access token"
    return 1
  fi
  
  echo "✓ Authentication successful!"
  echo ""
  echo "Token Details:"
  echo "  Expires in: ${EXPIRES_IN} seconds"
  echo ""
  
  # Decode and display token claims
  echo "Token Claims:"
  PAYLOAD=$(decode_jwt_payload "$ACCESS_TOKEN")
  echo "$PAYLOAD" | python3 -m json.tool 2>/dev/null || echo "$PAYLOAD"
  echo ""
  
  # Save token to file
  TOKEN_FILE="${SCRIPT_DIR}/.${username}_token.json"
  echo "$TOKEN_RESPONSE" > "$TOKEN_FILE"
  echo "✓ Token saved to: $TOKEN_FILE"
  echo ""
  
  # Display key claims
  echo "Key Claims:"
  echo "  Subject (sub): $(echo "$PAYLOAD" | grep -o '"sub":"[^"]*' | cut -d'"' -f4)"
  echo "  Username: $(echo "$PAYLOAD" | grep -o '"preferred_username":"[^"]*' | cut -d'"' -f4)"
  echo "  Email: $(echo "$PAYLOAD" | grep -o '"email":"[^"]*' | cut -d'"' -f4)"
  echo "  Issuer: $(echo "$PAYLOAD" | grep -o '"iss":"[^"]*' | cut -d'"' -f4)"
  echo "  Audience: $(echo "$PAYLOAD" | grep -o '"aud":"[^"]*' | cut -d'"' -f4)"
  
  # Extract and display groups
  GROUPS=$(echo "$PAYLOAD" | grep -o '"groups":\[[^]]*\]' | sed 's/"groups"://; s/\[//; s/\]//; s/"//g')
  if [ -n "$GROUPS" ]; then
    echo "  Groups: $GROUPS"
  fi
  
  # Extract and display roles
  ROLES=$(echo "$PAYLOAD" | grep -o '"roles":\[[^]]*\]' | sed 's/"roles"://; s/\[//; s/\]//; s/"//g')
  if [ -n "$ROLES" ]; then
    echo "  Roles: $ROLES"
  fi
  
  echo ""
  echo "Access Token (first 50 chars): ${ACCESS_TOKEN:0:50}..."
  echo ""
}

# Demo for admin user
echo "========================================="
echo "1. Admin User Authentication"
echo "========================================="
get_oidc_token "admin" "admin123"

echo ""
echo "========================================="
echo "2. Developer User Authentication"
echo "========================================="
get_oidc_token "developer" "dev123"

echo ""
echo "========================================="
echo "3. Auditor User Authentication"
echo "========================================="
get_oidc_token "auditor" "audit123"

echo ""
echo "========================================="
echo "Demo 1 Complete!"
echo "========================================="
echo ""
echo "Tokens have been saved and can be used in the next demo."
echo "Run: ./02-assume-role-with-web-identity.sh"

