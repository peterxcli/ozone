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

# Demo: Exchange OIDC token for temporary S3 credentials via STS

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STS_ENDPOINT="${STS_ENDPOINT:-http://localhost:9878}"

echo "========================================="
echo "Demo 2: AssumeRoleWithWebIdentity"
echo "========================================="
echo ""
echo "This demo exchanges OIDC tokens for temporary S3 credentials"
echo ""

# Function to assume role with web identity
assume_role_with_web_identity() {
  local username=$1
  local role_arn=$2
  local session_name=$3
  
  TOKEN_FILE="${SCRIPT_DIR}/.${username}_token.json"
  
  if [ ! -f "$TOKEN_FILE" ]; then
    echo "ERROR: Token file not found for user $username"
    echo "Please run 01-authenticate-and-get-token.sh first"
    return 1
  fi
  
  # Extract access token from file
  WEB_IDENTITY_TOKEN=$(cat "$TOKEN_FILE" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
  
  if [ -z "$WEB_IDENTITY_TOKEN" ]; then
    echo "ERROR: Failed to extract access token for $username"
    return 1
  fi
  
  echo "User: $username"
  echo "Role ARN: $role_arn"
  echo "Session Name: $session_name"
  echo "STS Endpoint: $STS_ENDPOINT"
  echo ""
  
  # Call STS AssumeRoleWithWebIdentity
  echo "Calling AssumeRoleWithWebIdentity..."
  
  STS_RESPONSE=$(curl -sf -X POST "$STS_ENDPOINT" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "Action=AssumeRoleWithWebIdentity" \
    -d "Version=2011-06-15" \
    -d "WebIdentityToken=${WEB_IDENTITY_TOKEN}" \
    -d "RoleArn=${role_arn}" \
    -d "RoleSessionName=${session_name}" \
    -d "DurationSeconds=3600")
  
  if [ $? -ne 0 ] || [ -z "$STS_RESPONSE" ]; then
    echo "ERROR: STS request failed"
    return 1
  fi
  
  # Check for errors in response
  if echo "$STS_RESPONSE" | grep -q "<Error>"; then
    echo "ERROR: STS returned an error:"
    echo "$STS_RESPONSE"
    return 1
  fi
  
  echo "✓ STS AssumeRoleWithWebIdentity successful!"
  echo ""
  
  # Parse XML response and extract credentials
  ACCESS_KEY_ID=$(echo "$STS_RESPONSE" | grep -o '<AccessKeyId>[^<]*' | sed 's/<AccessKeyId>//')
  SECRET_ACCESS_KEY=$(echo "$STS_RESPONSE" | grep -o '<SecretAccessKey>[^<]*' | sed 's/<SecretAccessKey>//')
  SESSION_TOKEN=$(echo "$STS_RESPONSE" | grep -o '<SessionToken>[^<]*' | sed 's/<SessionToken>//')
  EXPIRATION=$(echo "$STS_RESPONSE" | grep -o '<Expiration>[^<]*' | sed 's/<Expiration>//')
  
  if [ -z "$ACCESS_KEY_ID" ] || [ -z "$SECRET_ACCESS_KEY" ]; then
    echo "ERROR: Failed to extract credentials from STS response"
    echo "Response:"
    echo "$STS_RESPONSE"
    return 1
  fi
  
  echo "Temporary S3 Credentials:"
  echo "  Access Key ID:     $ACCESS_KEY_ID"
  echo "  Secret Access Key: ${SECRET_ACCESS_KEY:0:20}..."
  echo "  Session Token:     ${SESSION_TOKEN:0:50}..."
  echo "  Expiration:        $EXPIRATION"
  echo ""
  
  # Save credentials to file
  CREDS_FILE="${SCRIPT_DIR}/.${username}_credentials.sh"
  cat > "$CREDS_FILE" <<EOF
# Temporary S3 credentials for $username
# Generated at: $(date)
# Expires at: $EXPIRATION

export AWS_ACCESS_KEY_ID="$ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$SECRET_ACCESS_KEY"
export AWS_SESSION_TOKEN="$SESSION_TOKEN"
export AWS_DEFAULT_REGION="us-east-1"
EOF
  
  echo "✓ Credentials saved to: $CREDS_FILE"
  echo ""
  echo "To use these credentials, run:"
  echo "  source $CREDS_FILE"
  echo ""
  
  # Also save as JSON for programmatic use
  CREDS_JSON="${SCRIPT_DIR}/.${username}_credentials.json"
  cat > "$CREDS_JSON" <<EOF
{
  "AccessKeyId": "$ACCESS_KEY_ID",
  "SecretAccessKey": "$SECRET_ACCESS_KEY",
  "SessionToken": "$SESSION_TOKEN",
  "Expiration": "$EXPIRATION"
}
EOF
  
  echo "✓ Credentials also saved as JSON: $CREDS_JSON"
  echo ""
}

# Demo for admin user
echo "========================================="
echo "1. Admin User - AssumeRole"
echo "========================================="
assume_role_with_web_identity "admin" \
  "arn:ozone:iam::admin-role" \
  "admin-session-$(date +%s)"

echo ""
echo "========================================="
echo "2. Developer User - AssumeRole"
echo "========================================="
assume_role_with_web_identity "developer" \
  "arn:ozone:iam::developer-role" \
  "dev-session-$(date +%s)"

echo ""
echo "========================================="
echo "3. Auditor User - AssumeRole"
echo "========================================="
assume_role_with_web_identity "auditor" \
  "arn:ozone:iam::auditor-role" \
  "auditor-session-$(date +%s)"

echo ""
echo "========================================="
echo "Demo 2 Complete!"
echo "========================================="
echo ""
echo "S3 credentials have been generated for all users."
echo "Run: ./03-use-s3-credentials.sh to test S3 operations"

