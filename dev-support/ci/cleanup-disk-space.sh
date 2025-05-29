#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

#
# This script frees up disk space on CI systems by removing unneeded packages
# and large directories. This is particularly useful for builds that require
# significant disk space, such as RocksDB native builds.
#

echo "=============================================================================="
echo "Freeing up disk space on CI system"
echo "=============================================================================="

echo "Listing 100 largest packages"
dpkg-query -Wf '${Installed-Size}\t${Package}\n' | sort -n | tail -n 100
df -h

echo "Removing large packages"
sudo apt-get remove -y '^ghc-8.*'
sudo apt-get remove -y 'php.*'
sudo apt-get remove -y azure-cli google-cloud-sdk hhvm google-chrome-stable firefox powershell mono-devel
sudo apt-get autoremove -y
sudo apt-get clean

df -h

echo "Removing large directories"
# Remove .NET SDK (typically 15GB+)
rm -rf /usr/share/dotnet/

# Remove Maven repository cache (if needed)
if [ "${CLEAN_MAVEN_CACHE}" = "true" ]; then
    echo "Cleaning Maven repository cache"
    rm -rf ~/.m2/repository
fi

# Remove RocksDB build artifacts (if needed)
if [ "${CLEAN_ROCKSDB_BUILD}" = "true" ]; then
    echo "Cleaning RocksDB build artifacts"
    find . -name "rocksdb-*" -type d -exec rm -rf {} +
fi

df -h

echo "=============================================================================="
echo "Disk space cleanup completed"
echo "==============================================================================" 