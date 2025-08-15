# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Apache Ozone Overview

Apache Ozone is a scalable, redundant, and distributed object store optimized for big data workloads. It provides a S3-compatible interface and native Ozone APIs.

## Essential Commands

### Building
```bash
# Quick build (skip tests)
mvn clean install -DskipTests

# Build with distribution package
mvn clean install -Pdist -DskipTests

# Faster build options
mvn clean install -DskipTests -DskipShade -DskipRecon
```

### Testing
```bash
# Run all unit tests
mvn test

# Run specific test class
mvn test -Dtest=TestClassName

# Run specific test method
mvn test -Dtest=TestClassName#methodName

# Run integration tests
mvn verify -pl :ozone-integration-test

# Run acceptance tests (requires Docker)
hadoop-ozone/dev-support/checks/acceptance.sh
```

### Code Quality
```bash
# Run checkstyle
hadoop-ozone/dev-support/checks/checkstyle.sh

# Run SpotBugs
hadoop-ozone/dev-support/checks/findbugs.sh

# Run PMD
hadoop-ozone/dev-support/checks/pmd.sh

# Check licenses
hadoop-ozone/dev-support/checks/rat.sh
```

## Architecture

### Core Components

1. **Storage Container Manager (SCM)** - Manages block space and container lifecycle
   - Entry point: `hadoop-hdds/server-scm/src/main/java/org/apache/hadoop/hdds/scm/server/StorageContainerManagerStarter.java`
   - Handles datanode registration and heartbeats
   - Manages container placement and replication

2. **Ozone Manager (OM)** - Manages namespace (volumes, buckets, keys)
   - Entry point: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/OzoneManager.java`
   - Uses Apache Ratis for high availability
   - Handles all metadata operations

3. **Datanodes** - Store actual data in containers
   - Container service implementation in `hadoop-hdds/container-service/`
   - Report to SCM via heartbeat protocol

4. **Recon** - Monitoring and management service
   - Located in `hadoop-ozone/recon/`
   - Provides unified UI for cluster management

5. **S3 Gateway** - S3-compatible API
   - Located in `hadoop-ozone/s3gateway/`
   - Translates S3 operations to Ozone operations

### Key Concepts

- **Containers**: Replication units (default 5GB), managed by SCM
- **Blocks**: Allocation units (default 256MB) within containers
- **Pipelines**: Groups of datanodes that replicate containers
- **Layout Features**: Version control for backward-incompatible changes
  - OM features: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/upgrade/OMLayoutFeature.java`
  - HDDS features: `hadoop-hdds/common/src/main/java/org/apache/hadoop/hdds/upgrade/HDDSLayoutFeature.java`

### Protocol Buffers

All RPC protocols are defined in `.proto` files:
- Common protocols: `hadoop-hdds/interface-client/src/main/proto/`
- OM protocols: `hadoop-ozone/interface-client/src/main/proto/`

### Testing Infrastructure

- `MiniOzoneCluster`: In-memory cluster for unit/integration tests
- Robot Framework: Used for acceptance tests with Docker Compose
- Test utilities in `hadoop-hdds/test-utils/` and `hadoop-ozone/integration-test/`

## Development Guidelines

- Code style: 2 spaces, 120-char lines (configured in `.editorconfig`)
- All changes require JIRA issue (HDDS-XXXX or HDDS-XXXXX)
- PR title format: "HDDS-XXXXX. Brief description"
- Run local CI checks before submitting PR
- Enable GitHub Actions in your fork for automated testing

## Running Locally

IntelliJ run configurations are provided in `.run/` directory. Start order:
1. SCMInit (first time only)
2. SCM
3. OMInit (first time only)
4. OM
5. Recon (optional)
6. Datanode(s)

## Key Directories

- `hadoop-hdds/` - Core storage layer (HDDS)
- `hadoop-ozone/` - Ozone-specific components
- `hadoop-ozone/dev-support/checks/` - CI scripts (can run locally)
- `hadoop-ozone/dist/` - Distribution packaging
- `hadoop-ozone/docs/` - Documentation source