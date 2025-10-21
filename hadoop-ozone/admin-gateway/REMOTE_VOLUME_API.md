# Remote Volume API

## Overview

The Remote Volume API provides a clean, streamlined interface for creating JuiceFS-backed remote volumes in Apache Ozone. This API handles the complete lifecycle of remote volume creation, including:

1. Metadata store database creation (for internal sources)
2. Bucket and user creation with S3 credentials
3. JuiceFS filesystem formatting
4. Catalog recording for tracking

## API Endpoint

### Create Remote Volume

**Endpoint:** `POST /remote-volumes`

**Description:** Creates a new remote volume with JuiceFS backing.

#### Request Body

```json
{
  "owner_id": "user123",
  "remote_volume_name": "my-volume",
  "metadata_store_source": "internal",
  "metadata_store_info": {
    "connection_string": "postgres://host:port/db",
    "username": "dbuser",
    "password": "dbpass"
  },
  "additional_juicefs_command": "--block-size 4M"
}
```

#### Request Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `owner_id` | string | Yes | User ID of the volume owner |
| `remote_volume_name` | string | Yes | Name of the remote volume |
| `metadata_store_source` | enum | Yes | Source type: `internal`, `external`, or `managed` (managed not yet supported) |
| `metadata_store_info` | object | Conditional | Required for `external` source. Contains connection details |
| `metadata_store_info.connection_string` | string | For external | PostgreSQL connection string |
| `metadata_store_info.username` | string | Optional | Database username (if needed) |
| `metadata_store_info.password` | string | Optional | Database password (if needed) |
| `additional_juicefs_command` | string | Optional | Additional JuiceFS format command arguments |

#### Metadata Store Sources

- **internal**: Automatically creates a new PostgreSQL database and user with restricted permissions
  - Database name: `remote_volume_{sanitized_name}`
  - User name: `remote_volume_{sanitized_name}_user`
  - Isolated database per volume

- **external**: Uses an existing external PostgreSQL database
  - Requires `connection_string` in `metadata_store_info`
  - User manages database lifecycle

- **managed**: (Not yet supported) Future managed service option

#### Response

**Success (201 Created):**

```json
{
  "remote_volume_id": "550e8400-e29b-41d4-a716-446655440000",
  "remote_volume_name": "my-volume",
  "owner_id": "user123",
  "bucket_name": "remote_volume_my-volume",
  "access_key": "AKOA123456789ABC",
  "secret_key": "dGhpc2lzYXNlY3JldGtleQ==",
  "metadata_connection_string": "postgres://user:pass@host:5432/db",
  "status": "success",
  "message": "Remote volume created successfully"
}
```

**Error (400 Bad Request):**

```json
{
  "error": "Owner ID is required"
}
```

**Error (409 Conflict):**

```json
{
  "error": "Database already exists: remote_volume_my_volume"
}
```

**Error (500 Internal Server Error):**

```json
{
  "error": "Internal server error: <error details>"
}
```

## Workflow

The remote volume creation follows this workflow:

### 1. Metadata Store Creation (Internal Only)

For `internal` metadata source:
- Creates database user: `remote_volume_{name}_user`
- Creates database: `remote_volume_{name}`
- Grants ownership to the created user
- Returns connection string with credentials

For `external` metadata source:
- Uses provided connection string as-is
- No database creation

### 2. Bucket and Credentials Creation

- Creates S3 bucket: `remote_volume_{name}`
- Generates AWS-compatible access key (format: `AKOA{prefix}{random}`)
- Generates secure secret key (base64-encoded random bytes)
- Associates bucket with owner

### 3. JuiceFS Format

Executes JuiceFS format command:

```bash
juicefs format \
  --storage s3 \
  --bucket <s3_bucket_url> \
  --access-key <access_key> \
  --secret-key <secret_key> \
  [additional_commands] \
  <metadata_url> \
  <volume_name>
```

### 4. Catalog Recording

Records volume metadata in PostgreSQL catalog:
- Volume ID, name, owner
- Bucket and access credentials
- Metadata store information
- Creation timestamp
- Status tracking

## Configuration

Required configuration in `ozone-site.xml`:

```xml
<!-- Catalog Database Configuration -->
<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.host</name>
  <value>localhost</value>
</property>

<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.port</name>
  <value>5432</value>
</property>

<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.name</name>
  <value>ozone_catalog</value>
</property>

<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.username</name>
  <value>postgres</value>
</property>

<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.password</name>
  <value>your_password</value>
</property>
```

## Example Usage

### Create with Internal Metadata Store

```bash
curl -X POST http://localhost:9842/remote-volumes \
  -H "Content-Type: application/json" \
  -d '{
    "owner_id": "alice",
    "remote_volume_name": "alice-data",
    "metadata_store_source": "internal"
  }'
```

### Create with External Metadata Store

```bash
curl -X POST http://localhost:9842/remote-volumes \
  -H "Content-Type: application/json" \
  -d '{
    "owner_id": "bob",
    "remote_volume_name": "bob-storage",
    "metadata_store_source": "external",
    "metadata_store_info": {
      "connection_string": "postgres://dbuser:dbpass@db.example.com:5432/juicefs_meta"
    }
  }'
```

### Create with Additional JuiceFS Options

```bash
curl -X POST http://localhost:9842/remote-volumes \
  -H "Content-Type: application/json" \
  -d '{
    "owner_id": "charlie",
    "remote_volume_name": "charlie-files",
    "metadata_store_source": "internal",
    "additional_juicefs_command": "--block-size 4M --compress lz4"
  }'
```

## Security Considerations

1. **Secret Key Storage**: Secret keys are hashed before being stored in the catalog
2. **Database Isolation**: Each internal volume gets its own database and user
3. **Credential Generation**: Uses `SecureRandom` for cryptographic randomness
4. **SQL Injection Prevention**: Uses prepared statements for all database operations

## Error Handling

The API includes comprehensive error handling:

- **Validation errors** (400): Invalid input parameters
- **Conflict errors** (409): Resource already exists
- **Server errors** (500): Database failures, JuiceFS errors, etc.

On failure, the API attempts to clean up partially created resources:
- Deletes created buckets
- Drops created databases (for internal source)
- Logs cleanup failures for manual intervention

## Implementation Files

- `RemoteVolumeEndpoint.java` - REST endpoint
- `RemoteVolumeService.java` - Core business logic
- `RemoteVolumeCatalog.java` - Catalog database operations
- `CreateRemoteVolumeRequest.java` - Request model
- `CreateRemoteVolumeResponse.java` - Response model
- `MetadataStoreSource.java` - Enum for source types
- `MetadataStoreInfo.java` - Metadata connection info
- `RemoteVolumeAlreadyExistsException.java` - Custom exception

## Future Enhancements

1. **Managed Metadata Store**: Support for fully managed metadata service
2. **Volume Deletion**: API for cleaning up remote volumes
3. **Volume Listing**: Query volumes by owner or status
4. **Credential Rotation**: Automatic key rotation support
5. **Volume Snapshots**: JuiceFS snapshot management
6. **Usage Metrics**: Track volume usage and performance
