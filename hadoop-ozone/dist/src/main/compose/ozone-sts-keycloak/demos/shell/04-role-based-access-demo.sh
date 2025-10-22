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

# Demo: Role-based access control scenarios

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
S3_ENDPOINT="${S3_ENDPOINT:-http://localhost:9878}"

echo "========================================="
echo "Demo 4: Role-Based Access Control"
echo "========================================="
echo ""
echo "This demo demonstrates different access levels based on user roles"
echo ""

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
  echo "ERROR: AWS CLI is not installed"
  echo "Please install it: pip install awscli"
  exit 1
fi

# Function to run test with specific credentials
run_test() {
  local username=$1
  local test_name=$2
  local command=$3
  
  CREDS_FILE="${SCRIPT_DIR}/.${username}_credentials.sh"
  
  if [ ! -f "$CREDS_FILE" ]; then
    echo "   ⚠ Credentials not found for $username, skipping"
    return
  fi
  
  # Source credentials in subshell to avoid pollution
  (
    source "$CREDS_FILE"
    export AWS_ENDPOINT_URL="$S3_ENDPOINT"
    
    echo -n "   $username: "
    if eval "$command" &>/dev/null; then
      echo "✓ ALLOWED"
    else
      echo "✗ DENIED"
    fi
  )
}

echo "========================================="
echo "Scenario 1: Bucket Creation"
echo "========================================="
echo ""
echo "Testing who can create buckets..."
echo ""

run_test "admin" "Create bucket" \
  "aws s3 mb s3://admin-test-bucket --endpoint-url $S3_ENDPOINT"
run_test "developer" "Create bucket" \
  "aws s3 mb s3://dev-test-bucket --endpoint-url $S3_ENDPOINT"
run_test "auditor" "Create bucket" \
  "aws s3 mb s3://auditor-test-bucket --endpoint-url $S3_ENDPOINT"

echo ""
echo "Expected: Admin ✓, Developer ✗, Auditor ✗"
echo ""

echo "========================================="
echo "Scenario 2: Object Upload (Write)"
echo "========================================="
echo ""
echo "Testing who can upload objects to shared-bucket..."
echo ""

# Create test files
echo "test data" > /tmp/admin-upload.txt
echo "test data" > /tmp/dev-upload.txt
echo "test data" > /tmp/auditor-upload.txt

run_test "admin" "Upload object" \
  "aws s3 cp /tmp/admin-upload.txt s3://shared-bucket/admin-file.txt --endpoint-url $S3_ENDPOINT"
run_test "developer" "Upload object" \
  "aws s3 cp /tmp/dev-upload.txt s3://shared-bucket/dev-file.txt --endpoint-url $S3_ENDPOINT"
run_test "auditor" "Upload object" \
  "aws s3 cp /tmp/auditor-upload.txt s3://shared-bucket/auditor-file.txt --endpoint-url $S3_ENDPOINT"

rm -f /tmp/admin-upload.txt /tmp/dev-upload.txt /tmp/auditor-upload.txt

echo ""
echo "Expected: Admin ✓, Developer ✓, Auditor ✗"
echo ""

echo "========================================="
echo "Scenario 3: Object Download (Read)"
echo "========================================="
echo ""
echo "Testing who can read objects from shared-bucket..."
echo ""

run_test "admin" "Download object" \
  "aws s3 cp s3://shared-bucket/admin-file.txt /tmp/admin-download.txt --endpoint-url $S3_ENDPOINT"
run_test "developer" "Download object" \
  "aws s3 cp s3://shared-bucket/admin-file.txt /tmp/dev-download.txt --endpoint-url $S3_ENDPOINT"
run_test "auditor" "Download object" \
  "aws s3 cp s3://shared-bucket/admin-file.txt /tmp/auditor-download.txt --endpoint-url $S3_ENDPOINT"

rm -f /tmp/admin-download.txt /tmp/dev-download.txt /tmp/auditor-download.txt

echo ""
echo "Expected: Admin ✓, Developer ✓, Auditor ✓"
echo ""

echo "========================================="
echo "Scenario 4: Object Deletion"
echo "========================================="
echo ""
echo "Testing who can delete objects from shared-bucket..."
echo ""

run_test "admin" "Delete object" \
  "aws s3 rm s3://shared-bucket/dev-file.txt --endpoint-url $S3_ENDPOINT"
run_test "developer" "Delete object" \
  "aws s3 rm s3://shared-bucket/admin-file.txt --endpoint-url $S3_ENDPOINT"
run_test "auditor" "Delete object" \
  "aws s3 rm s3://shared-bucket/test-auditor.txt --endpoint-url $S3_ENDPOINT"

echo ""
echo "Expected: Admin ✓, Developer ✓, Auditor ✗"
echo ""

echo "========================================="
echo "Scenario 5: Bucket Deletion"
echo "========================================="
echo ""
echo "Testing who can delete buckets..."
echo ""

run_test "admin" "Delete bucket" \
  "aws s3 rb s3://admin-test-bucket --endpoint-url $S3_ENDPOINT"
run_test "developer" "Delete bucket" \
  "aws s3 rb s3://dev-bucket --endpoint-url $S3_ENDPOINT"
run_test "auditor" "Delete bucket" \
  "aws s3 rb s3://admin-bucket --endpoint-url $S3_ENDPOINT"

echo ""
echo "Expected: Admin ✓, Developer ✗, Auditor ✗"
echo ""

echo "========================================="
echo "Access Control Summary"
echo "========================================="
echo ""
echo "Role: Admin (s3-admin, s3-full-access)"
echo "  ✓ Create buckets"
echo "  ✓ Delete buckets"
echo "  ✓ Upload objects"
echo "  ✓ Download objects"
echo "  ✓ Delete objects"
echo ""
echo "Role: Developer (s3-full-access)"
echo "  ✗ Create buckets"
echo "  ✗ Delete buckets"
echo "  ✓ Upload objects"
echo "  ✓ Download objects"
echo "  ✓ Delete objects"
echo ""
echo "Role: Auditor (s3-read-only)"
echo "  ✗ Create buckets"
echo "  ✗ Delete buckets"
echo "  ✗ Upload objects"
echo "  ✓ Download objects"
echo "  ✗ Delete objects"
echo ""
echo "========================================="
echo "Demo 4 Complete!"
echo "========================================="
echo ""
echo "This demonstrates how Keycloak roles are mapped to S3 permissions"
echo "through the STS service."

