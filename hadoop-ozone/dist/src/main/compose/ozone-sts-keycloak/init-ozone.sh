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
echo "Initializing Ozone cluster..."
echo "========================================="

OM_URL="http://localhost:9874"
S3G_URL="http://localhost:9878"
RECON_URL="http://localhost:9888"

# Wait for OM to be ready
echo "Waiting for Ozone Manager..."
max_attempts=60
attempt=0
until curl -sf "${OM_URL}" > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ $attempt -ge $max_attempts ]; then
    echo "ERROR: Ozone Manager failed to start"
    exit 1
  fi
  echo "  Attempt $attempt/$max_attempts: Waiting for OM..."
  sleep 5
done
echo "✓ Ozone Manager is ready"

# Wait for S3 Gateway
echo ""
echo "Waiting for S3 Gateway..."
attempt=0
until curl -sf "${S3G_URL}" > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ $attempt -ge $max_attempts ]; then
    echo "ERROR: S3 Gateway failed to start"
    exit 1
  fi
  echo "  Attempt $attempt/$max_attempts: Waiting for S3G..."
  sleep 5
done
echo "✓ S3 Gateway is ready"

# Check STS health endpoint
echo ""
echo "Verifying STS service..."
STS_HEALTH=$(curl -sf "${S3G_URL}/health" 2>/dev/null || echo "")
if echo "$STS_HEALTH" | grep -q "STS service is running"; then
  echo "✓ STS service is enabled and running"
else
  echo "⚠ Warning: STS health check returned: $STS_HEALTH"
fi

# Create test volumes and buckets
echo ""
echo "Creating test volumes and buckets..."

docker exec om ozone sh volume create /s3v 2>/dev/null || echo "  Volume /s3v already exists or creation not needed"

docker exec om ozone sh bucket create /s3v/admin-bucket 2>/dev/null || echo "  Bucket /s3v/admin-bucket already exists"
docker exec om ozone sh bucket create /s3v/dev-bucket 2>/dev/null || echo "  Bucket /s3v/dev-bucket already exists"
docker exec om ozone sh bucket create /s3v/shared-bucket 2>/dev/null || echo "  Bucket /s3v/shared-bucket already exists"

echo "✓ Test buckets created:"
echo "  - admin-bucket (for admin user)"
echo "  - dev-bucket (for developer user)"
echo "  - shared-bucket (for all users)"

echo ""
echo "========================================="
echo "Ozone Configuration Summary"
echo "========================================="
echo "Ozone Manager:   ${OM_URL}"
echo "S3 Gateway:      ${S3G_URL}"
echo "Recon:           ${RECON_URL}"
echo "STS Endpoint:    ${S3G_URL}/"
echo ""
echo "STS Configuration:"
docker exec s3g printenv | grep "OZONE.*STS" | sed 's/OZONE-SITE.XML_/  /' | sort
echo ""
echo "========================================="
echo "Ozone initialization complete!"
echo "========================================="
echo ""
echo "Next steps:"
echo "  1. Run demos/shell/01-authenticate-and-get-token.sh to get OIDC tokens"
echo "  2. Run demos/shell/02-assume-role-with-web-identity.sh to get S3 credentials"
echo "  3. Run demos/shell/03-use-s3-credentials.sh to test S3 operations"
echo "  4. Or run demos/python/demo_full_workflow.py for a complete Python demo"

