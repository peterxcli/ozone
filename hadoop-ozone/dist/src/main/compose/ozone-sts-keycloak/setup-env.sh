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

# Setup script for environment variables

echo "Setting up environment variables for Ozone STS Keycloak demo..."

# Export environment variables
export OZONE_RUNNER_IMAGE=apache/ozone-runner
export OZONE_RUNNER_VERSION=20241015-1.4.0
export OZONE_REPLICATION_FACTOR=1
export OZONE_SAFEMODE_MIN_DATANODES=1

# Create .env file for docker-compose
cat > .env << EOF
# Ozone Runner Configuration
OZONE_RUNNER_IMAGE=${OZONE_RUNNER_IMAGE}
OZONE_RUNNER_VERSION=${OZONE_RUNNER_VERSION}

# Ozone Cluster Configuration
OZONE_REPLICATION_FACTOR=${OZONE_REPLICATION_FACTOR}
OZONE_SAFEMODE_MIN_DATANODES=${OZONE_SAFEMODE_MIN_DATANODES}
EOF

echo "✓ Environment variables exported"
echo "✓ .env file created"
echo ""
echo "Current environment:"
echo "  OZONE_RUNNER_IMAGE: ${OZONE_RUNNER_IMAGE}"
echo "  OZONE_RUNNER_VERSION: ${OZONE_RUNNER_VERSION}"
echo "  OZONE_REPLICATION_FACTOR: ${OZONE_REPLICATION_FACTOR}"
echo "  OZONE_SAFEMODE_MIN_DATANODES: ${OZONE_SAFEMODE_MIN_DATANODES}"
echo ""
echo "To use these variables in your current shell, run:"
echo "  source ./setup-env.sh"
echo ""
echo "Or simply use docker-compose which will read the .env file automatically:"
echo "  docker-compose up -d"

