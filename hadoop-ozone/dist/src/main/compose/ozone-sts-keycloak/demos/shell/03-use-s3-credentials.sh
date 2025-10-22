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

# Demo: Use temporary S3 credentials to perform S3 operations

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
S3_ENDPOINT="${S3_ENDPOINT:-http://localhost:9878}"

echo "========================================="
echo "Demo 3: Use S3 Credentials"
echo "========================================="
echo ""
echo "This demo uses temporary credentials to perform S3 operations"
echo ""

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
  echo "ERROR: AWS CLI is not installed"
  echo "Please install it: pip install awscli"
  exit 1
fi

# Function to test S3 operations with credentials
test_s3_operations() {
  local username=$1
  
  CREDS_FILE="${SCRIPT_DIR}/.${username}_credentials.sh"
  
  if [ ! -f "$CREDS_FILE" ]; then
    echo "ERROR: Credentials file not found for user $username"
    echo "Please run 02-assume-role-with-web-identity.sh first"
    return 1
  fi
  
  echo "Testing S3 operations for user: $username"
  echo ""
  
  # Source credentials
  source "$CREDS_FILE"
  
  echo "Credentials loaded:"
  echo "  Access Key ID: $AWS_ACCESS_KEY_ID"
  echo ""
  
  # Configure AWS CLI to use Ozone S3
  export AWS_ENDPOINT_URL="$S3_ENDPOINT"
  
  # Test: List buckets
  echo "1. Listing buckets..."
  if aws s3 ls --endpoint-url "$S3_ENDPOINT" 2>/dev/null; then
    echo "   ✓ List buckets successful"
  else
    echo "   ✗ List buckets failed (may be expected based on permissions)"
  fi
  echo ""
  
  # Test: Create a test file
  TEST_FILE="/tmp/test-${username}-$(date +%s).txt"
  echo "Test data from $username at $(date)" > "$TEST_FILE"
  
  # Test: Upload object to shared bucket
  echo "2. Uploading object to shared-bucket..."
  if aws s3 cp "$TEST_FILE" "s3://shared-bucket/test-${username}.txt" \
      --endpoint-url "$S3_ENDPOINT" 2>/dev/null; then
    echo "   ✓ Upload successful"
  else
    echo "   ✗ Upload failed (may be expected based on permissions)"
  fi
  echo ""
  
  # Test: List objects in shared bucket
  echo "3. Listing objects in shared-bucket..."
  if aws s3 ls "s3://shared-bucket/" --endpoint-url "$S3_ENDPOINT" 2>/dev/null; then
    echo "   ✓ List objects successful"
  else
    echo "   ✗ List objects failed (may be expected based on permissions)"
  fi
  echo ""
  
  # Test: Download object
  echo "4. Downloading object from shared-bucket..."
  DOWNLOAD_FILE="/tmp/download-${username}-$(date +%s).txt"
  if aws s3 cp "s3://shared-bucket/test-${username}.txt" "$DOWNLOAD_FILE" \
      --endpoint-url "$S3_ENDPOINT" 2>/dev/null; then
    echo "   ✓ Download successful"
    echo "   Content: $(cat "$DOWNLOAD_FILE")"
    rm -f "$DOWNLOAD_FILE"
  else
    echo "   ✗ Download failed (may be expected based on permissions)"
  fi
  echo ""
  
  # Test: Try to create bucket (admin only)
  echo "5. Attempting to create bucket (admin-only operation)..."
  if aws s3 mb "s3://test-${username}-bucket" \
      --endpoint-url "$S3_ENDPOINT" 2>/dev/null; then
    echo "   ✓ Create bucket successful (user has admin permissions)"
  else
    echo "   ✗ Create bucket failed (expected for non-admin users)"
  fi
  echo ""
  
  # Cleanup
  rm -f "$TEST_FILE"
}

# Test for each user
echo "========================================="
echo "1. Admin User S3 Operations"
echo "========================================="
test_s3_operations "admin"

echo ""
echo "========================================="
echo "2. Developer User S3 Operations"
echo "========================================="
test_s3_operations "developer"

echo ""
echo "========================================="
echo "3. Auditor User S3 Operations"
echo "========================================="
test_s3_operations "auditor"

echo ""
echo "========================================="
echo "Demo 3 Complete!"
echo "========================================="
echo ""
echo "Summary:"
echo "  - Admin users should have full access (read, write, create buckets)"
echo "  - Developer users should have read/write access to objects"
echo "  - Auditor users should have read-only access"
echo ""
echo "Run: ./04-role-based-access-demo.sh for detailed RBAC scenarios"

