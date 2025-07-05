# Apache Ozone Operator

A Kubernetes operator for managing Apache Ozone clusters.

## Features

- **Automated Deployment**: Deploy Ozone clusters with a single CR
- **High Availability**: Built-in support for HA configurations
- **Rolling Upgrades**: Automated rolling upgrades with health checks
- **Auto-scaling**: Scale components up/down based on workload
- **Backup/Restore**: Scheduled backups to S3 or PVC
- **Health Monitoring**: Automatic health checks and self-healing
- **Security**: Support for TLS and Kerberos
- **Monitoring Integration**: Prometheus and Grafana support

## Prerequisites

- Kubernetes 1.19+
- kubectl configured to access your cluster
- cert-manager (for webhook certificates)

## Installation

### Install CRDs

```bash
kubectl apply -f config/crd/bases/ozone.apache.org_ozoneclusters.yaml
```

### Install Operator

```bash
kubectl apply -f deploy/operator.yaml
```

## Usage

### Deploy a Basic Ozone Cluster

```yaml
apiVersion: ozone.apache.org/v1alpha1
kind: OzoneCluster
metadata:
  name: my-ozone
spec:
  version: "1.4.0"
  scm:
    replicas: 3
    enableHA: true
    storageSize: 10Gi
  om:
    replicas: 3
    enableHA: true
    storageSize: 10Gi
  datanodes:
    replicas: 3
    dataVolumes:
    - size: 100Gi
```

Apply the manifest:

```bash
kubectl apply -f config/samples/ozone_v1alpha1_ozonecluster.yaml
```

### Check Cluster Status

```bash
kubectl get ozonecluster
kubectl describe ozonecluster my-ozone
```

## Configuration

### Storage Container Manager (SCM)

| Field | Description | Default |
|-------|-------------|---------|
| replicas | Number of SCM instances | 3 |
| enableHA | Enable high availability | true |
| storageSize | Metadata storage size | 10Gi |
| resources | CPU/Memory resources | - |

### Ozone Manager (OM)

| Field | Description | Default |
|-------|-------------|---------|
| replicas | Number of OM instances | 3 |
| enableHA | Enable high availability | true |
| storageSize | Metadata storage size | 10Gi |
| resources | CPU/Memory resources | - |

### Datanodes

| Field | Description | Default |
|-------|-------------|---------|
| replicas | Number of datanode instances | 3 |
| dataVolumes | List of data volumes | Required |
| resources | CPU/Memory resources | - |

### Optional Components

#### S3 Gateway

```yaml
s3Gateway:
  enabled: true
  replicas: 2
  serviceType: LoadBalancer
```

#### Recon Service

```yaml
recon:
  enabled: true
  storageSize: 20Gi
```

### Security

```yaml
security:
  enabled: true
  tlsEnabled: true
  certificateSecret:
    name: ozone-tls-certs
```

### Monitoring

```yaml
monitoring:
  enabled: true
  prometheusOperator:
    serviceMonitor: true
    labels:
      prometheus: kube-prometheus
  grafanaDashboard:
    enabled: true
```

### Backup

```yaml
backup:
  enabled: true
  schedule: "0 2 * * *"
  destination: s3://backup-bucket/ozone
  s3Config:
    endpoint: s3.amazonaws.com
    credentialsSecret:
      name: s3-credentials
```

## Operations

### Scaling

Scale datanodes:

```bash
kubectl patch ozonecluster my-ozone --type='merge' -p '{"spec":{"datanodes":{"replicas":5}}}'
```

### Upgrading

Upgrade to a new version:

```bash
kubectl patch ozonecluster my-ozone --type='merge' -p '{"spec":{"version":"1.5.0"}}'
```

The operator will perform a rolling upgrade with health checks.

### Backup and Restore

Enable scheduled backups:

```yaml
spec:
  backup:
    enabled: true
    schedule: "0 2 * * *"
    destination: s3://my-bucket/backups
```

## Development

### Building

```bash
go build -o bin/manager main.go
```

### Running Locally

```bash
make install
make run
```

### Running Tests

```bash
go test ./...
```

## Architecture

The operator follows the standard Kubernetes controller pattern:

1. **CRD**: Defines the OzoneCluster resource
2. **Controller**: Watches OzoneCluster resources and reconciles state
3. **Webhooks**: Validates and defaults OzoneCluster specs
4. **Managers**: Handle specific operations (backup, upgrade, health)

### Components

- **SCM Controller**: Manages Storage Container Manager StatefulSet
- **OM Controller**: Manages Ozone Manager StatefulSet
- **Datanode Controller**: Manages Datanode StatefulSet
- **Service Controller**: Manages Kubernetes Services
- **ConfigMap Controller**: Manages configuration
- **Backup Manager**: Handles backup operations
- **Upgrade Manager**: Orchestrates rolling upgrades
- **Health Checker**: Monitors component health

## Troubleshooting

### Check Operator Logs

```bash
kubectl logs -n ozone-operator-system deployment/ozone-operator-controller-manager
```

### Common Issues

1. **Pods not starting**: Check PVC provisioning and resource limits
2. **HA not working**: Ensure odd number of replicas for SCM/OM
3. **Backup failing**: Verify S3 credentials and connectivity

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

Copyright 2024 The Apache Software Foundation

Licensed under the Apache License, Version 2.0