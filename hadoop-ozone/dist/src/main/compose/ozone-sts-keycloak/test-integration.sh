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

# Integration test script for Ozone STS + Keycloak demo

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_TOTAL=0

# Configuration
KEYCLOAK_URL="http://localhost:8080"
STS_ENDPOINT="http://localhost:9878"
S3_ENDPOINT="http://localhost:9878"
REALM="ozone"

echo "========================================="
echo "Ozone STS + Keycloak Integration Tests"
echo "========================================="
echo ""

# Helper functions
print_test() {
  echo -e "${YELLOW}[TEST]${NC} $1"
}

print_pass() {
  echo -e "${GREEN}[PASS]${NC} $1"
  TESTS_PASSED=$((TESTS_PASSED + 1))
}

print_fail() {
  echo -e "${RED}[FAIL]${NC} $1"
  TESTS_FAILED=$((TESTS_FAILED + 1))
}

run_test() {
  TESTS_TOTAL=$((TESTS_TOTAL + 1))
}

# Test 1: Check services are running
test_services_running() {
  run_test
  print_test "Checking if all services are running..."
  
  local all_running=true
  
  # Check Keycloak
  if curl -sf "${KEYCLOAK_URL}/health/ready" > /dev/null 2>&1; then
    echo "  ✓ Keycloak is running"
  else
    echo "  ✗ Keycloak is not ready"
    all_running=false
  fi
  
  # Check Ozone Manager
  if curl -sf "http://localhost:9874" > /dev/null 2>&1; then
    echo "  ✓ Ozone Manager is running"
  else
    echo "  ✗ Ozone Manager is not ready"
    all_running=false
  fi
  
  # Check S3 Gateway
  if curl -sf "${S3_ENDPOINT}" > /dev/null 2>&1; then
    echo "  ✓ S3 Gateway is running"
  else
    echo "  ✗ S3 Gateway is not ready"
    all_running=false
  fi
  
  if [ "$all_running" = true ]; then
    print_pass "All services are running"
  else
    print_fail "Some services are not running"
    return 1
  fi
}

# Test 2: Keycloak realm configuration
test_keycloak_realm() {
  run_test
  print_test "Verifying Keycloak realm configuration..."
  
  if curl -sf "${KEYCLOAK_URL}/realms/${REALM}" > /dev/null 2>&1; then
    print_pass "Keycloak realm '${REALM}' is configured"
  else
    print_fail "Keycloak realm '${REALM}' not found"
    return 1
  fi
}

# Test 3: OIDC token generation
test_oidc_token_generation() {
  run_test
  print_test "Testing OIDC token generation for each user..."
  
  local all_passed=true
  
  for user in admin developer auditor; do
    case $user in
      admin)     password="admin123" ;;
      developer) password="dev123" ;;
      auditor)   password="audit123" ;;
    esac
    
    token_response=$(curl -sf -X POST \
      "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
      -H "Content-Type: application/x-www-form-urlencoded" \
      -d "username=$user" \
      -d "password=$password" \
      -d "grant_type=password" \
      -d "client_id=ozone-s3" 2>/dev/null || echo "")
    
    if [ -n "$token_response" ]; then
      access_token=$(echo "$token_response" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
      if [ -n "$access_token" ]; then
        echo "  ✓ Got token for $user"
      else
        echo "  ✗ Failed to extract token for $user"
        all_passed=false
      fi
    else
      echo "  ✗ Failed to get token for $user"
      all_passed=false
    fi
  done
  
  if [ "$all_passed" = true ]; then
    print_pass "OIDC token generation working for all users"
  else
    print_fail "OIDC token generation failed for some users"
    return 1
  fi
}

# Test 4: STS health check
test_sts_health() {
  run_test
  print_test "Checking STS service health..."
  
  health_response=$(curl -sf "${STS_ENDPOINT}/health" 2>/dev/null || echo "")
  
  if echo "$health_response" | grep -q "STS service is running"; then
    print_pass "STS service is healthy"
  else
    print_fail "STS service health check failed: $health_response"
    return 1
  fi
}

# Test 5: STS token exchange
test_sts_token_exchange() {
  run_test
  print_test "Testing STS AssumeRoleWithWebIdentity..."
  
  # Get OIDC token for admin
  token_response=$(curl -sf -X POST \
    "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=admin" \
    -d "password=admin123" \
    -d "grant_type=password" \
    -d "client_id=ozone-s3" 2>/dev/null)
  
  access_token=$(echo "$token_response" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
  
  if [ -z "$access_token" ]; then
    print_fail "Failed to get OIDC token for STS test"
    return 1
  fi
  
  # Call STS
  sts_response=$(curl -sf -X POST "${STS_ENDPOINT}/" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "Action=AssumeRoleWithWebIdentity" \
    -d "Version=2011-06-15" \
    -d "WebIdentityToken=${access_token}" \
    -d "RoleArn=arn:ozone:iam::admin-role" \
    -d "RoleSessionName=test-session" \
    -d "DurationSeconds=3600" 2>/dev/null || echo "")
  
  if [ -n "$sts_response" ]; then
    # Check for credentials in response
    if echo "$sts_response" | grep -q "<AccessKeyId>"; then
      print_pass "STS AssumeRoleWithWebIdentity successful"
    elif echo "$sts_response" | grep -q "<Error>"; then
      error_msg=$(echo "$sts_response" | grep -o '<Message>[^<]*' | sed 's/<Message>//')
      print_fail "STS returned error: $error_msg"
      return 1
    else
      print_fail "STS response format unexpected"
      return 1
    fi
  else
    print_fail "STS request failed"
    return 1
  fi
}

# Test 6: S3 operations with temporary credentials
test_s3_operations() {
  run_test
  print_test "Testing S3 operations with temporary credentials..."
  
  # Check if AWS CLI is available
  if ! command -v aws &> /dev/null; then
    print_fail "AWS CLI not installed, skipping S3 operations test"
    return 1
  fi
  
  # Get credentials
  token_response=$(curl -sf -X POST \
    "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=admin" \
    -d "password=admin123" \
    -d "grant_type=password" \
    -d "client_id=ozone-s3" 2>/dev/null)
  
  access_token=$(echo "$token_response" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
  
  sts_response=$(curl -sf -X POST "${STS_ENDPOINT}/" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "Action=AssumeRoleWithWebIdentity" \
    -d "Version=2011-06-15" \
    -d "WebIdentityToken=${access_token}" \
    -d "RoleArn=arn:ozone:iam::admin-role" \
    -d "RoleSessionName=test-session" 2>/dev/null)
  
  export AWS_ACCESS_KEY_ID=$(echo "$sts_response" | grep -o '<AccessKeyId>[^<]*' | sed 's/<AccessKeyId>//')
  export AWS_SECRET_ACCESS_KEY=$(echo "$sts_response" | grep -o '<SecretAccessKey>[^<]*' | sed 's/<SecretAccessKey>//')
  export AWS_SESSION_TOKEN=$(echo "$sts_response" | grep -o '<SessionToken>[^<]*' | sed 's/<SessionToken>//')
  
  if [ -z "$AWS_ACCESS_KEY_ID" ]; then
    print_fail "Failed to get S3 credentials"
    return 1
  fi
  
  # Try to list buckets
  if aws s3 ls --endpoint-url "$S3_ENDPOINT" &>/dev/null; then
    print_pass "S3 operations with temporary credentials working"
  else
    print_fail "S3 list buckets failed with temporary credentials"
    return 1
  fi
}

# Test 7: Role-based access control
test_rbac() {
  run_test
  print_test "Testing role-based access control..."
  
  if ! command -v aws &> /dev/null; then
    print_fail "AWS CLI not installed, skipping RBAC test"
    return 1
  fi
  
  # Get credentials for auditor (read-only)
  token_response=$(curl -sf -X POST \
    "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=auditor" \
    -d "password=audit123" \
    -d "grant_type=password" \
    -d "client_id=ozone-s3" 2>/dev/null)
  
  access_token=$(echo "$token_response" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
  
  sts_response=$(curl -sf -X POST "${STS_ENDPOINT}/" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "Action=AssumeRoleWithWebIdentity" \
    -d "Version=2011-06-15" \
    -d "WebIdentityToken=${access_token}" \
    -d "RoleArn=arn:ozone:iam::auditor-role" \
    -d "RoleSessionName=test-session" 2>/dev/null)
  
  export AWS_ACCESS_KEY_ID=$(echo "$sts_response" | grep -o '<AccessKeyId>[^<]*' | sed 's/<AccessKeyId>//')
  export AWS_SECRET_ACCESS_KEY=$(echo "$sts_response" | grep -o '<SecretAccessKey>[^<]*' | sed 's/<SecretAccessKey>//')
  export AWS_SESSION_TOKEN=$(echo "$sts_response" | grep -o '<SessionToken>[^<]*' | sed 's/<SessionToken>//')
  
  # Auditor should NOT be able to create bucket
  if aws s3 mb s3://test-rbac-bucket --endpoint-url "$S3_ENDPOINT" 2>&1 | grep -q "AccessDenied"; then
    print_pass "RBAC working: auditor cannot create buckets"
  else
    print_fail "RBAC not working: auditor was able to create bucket"
    # Clean up if bucket was created
    aws s3 rb s3://test-rbac-bucket --endpoint-url "$S3_ENDPOINT" 2>/dev/null || true
    return 1
  fi
}

# Run all tests
echo "Starting integration tests..."
echo ""

test_services_running || true
test_keycloak_realm || true
test_oidc_token_generation || true
test_sts_health || true
test_sts_token_exchange || true
test_s3_operations || true
test_rbac || true

# Print summary
echo ""
echo "========================================="
echo "Test Summary"
echo "========================================="
echo "Total tests:  $TESTS_TOTAL"
echo -e "Passed:       ${GREEN}$TESTS_PASSED${NC}"
echo -e "Failed:       ${RED}$TESTS_FAILED${NC}"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
  echo -e "${GREEN}All tests passed!${NC}"
  exit 0
else
  echo -e "${RED}Some tests failed.${NC}"
  exit 1
fi

