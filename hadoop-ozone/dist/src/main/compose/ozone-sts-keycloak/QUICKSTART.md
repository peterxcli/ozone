# Quick Start Guide

Get the Ozone STS + Keycloak demo running in under 5 minutes!

## Prerequisites

- Docker and Docker Compose installed
- At least 8GB RAM available
- Ports 8080, 9874, 9876, 9878, 9888 available

## Step 1: Start Services (2 minutes)

```bash
cd hadoop-ozone/dist/src/main/compose/ozone-sts-keycloak

# Start all services
docker-compose up -d

# Wait for services to be ready
sleep 60
```

## Step 2: Initialize (1 minute)

```bash
# Initialize Keycloak
./init-keycloak.sh

# Initialize Ozone
./init-ozone.sh
```

## Step 3: Run Demo (2 minutes)

### Option A: Shell Demo (Recommended)

```bash
cd demos/shell

# Get OIDC tokens
./01-authenticate-and-get-token.sh

# Exchange for S3 credentials
./02-assume-role-with-web-identity.sh

# Use S3 credentials
./03-use-s3-credentials.sh
```

### Option B: Python Demo

```bash
cd demos/python

# Install dependencies
pip install -r requirements.txt

# Run demo
python demo_full_workflow.py
```

## What You'll See

1. **OIDC Authentication**: Users authenticate with Keycloak
2. **Token Exchange**: OIDC tokens → temporary S3 credentials
3. **S3 Operations**: Use credentials to access S3 API
4. **Role-Based Access**: Different users have different permissions

## Test Users

| Username  | Password  | Access Level        |
|-----------|-----------|---------------------|
| admin     | admin123  | Full admin access   |
| developer | dev123    | Read/write objects  |
| auditor   | audit123  | Read-only          |

## Endpoints

- **Keycloak UI**: http://localhost:8080/admin (admin/admin)
- **S3 Gateway**: http://localhost:9878
- **Ozone Manager**: http://localhost:9874
- **Recon**: http://localhost:9888

## Troubleshooting

**Services not ready?**
```bash
docker-compose ps
docker-compose logs keycloak
docker-compose logs s3g
```

**Clean slate?**
```bash
docker-compose down -v
```

## Next Steps

- Read [DEMO.md](DEMO.md) for detailed walkthrough
- Read [ARCHITECTURE.md](ARCHITECTURE.md) for technical details
- Explore Python demos for programmatic access
- Customize roles in `keycloak-realm.json`

## Support

- Apache Ozone: https://ozone.apache.org
- Documentation: [README.md](README.md)
- Issues: https://issues.apache.org/jira/browse/HDDS

