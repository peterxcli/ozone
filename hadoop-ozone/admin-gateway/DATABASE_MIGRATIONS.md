# Database Migrations for Remote Volume Catalog

## Overview

The Remote Volume Catalog uses [Flyway](https://flywaydb.org/) for database schema migrations. Flyway is a version control system for databases that allows you to evolve your database schema in a reliable and repeatable way across all environments.

## Migration Structure

Migrations are stored in:
```
hadoop-ozone/admin-gateway/src/main/resources/db/migration/
```

### Naming Convention

Migration files follow Flyway's versioned migration naming pattern:

```
V{version}__{description}.sql
```

Where:
- `V` - Prefix for versioned migrations (required)
- `{version}` - Version number (e.g., 1, 2, 3, or 1.0, 2.1, etc.)
- `__` - Double underscore separator (required)
- `{description}` - Human-readable description with underscores

**Examples:**
- `V1__create_remote_volume_catalog.sql`
- `V2__add_remote_volume_metrics.sql`
- `V3__add_quota_limits.sql`

## Current Migrations

### V1__create_remote_volume_catalog.sql

Creates the initial `remote_volume_catalog` table with:

**Columns:**
- `id` - Unique volume identifier (UUID)
- `remote_volume_name` - User-friendly volume name
- `owner_id` - Owner/tenant identifier
- `bucket_name` - Associated S3 bucket name
- `metadata_store_source` - Type: INTERNAL, EXTERNAL, or MANAGED
- `metadata_connection_string` - PostgreSQL connection string
- `access_key` - AWS-compatible access key
- `secret_key_hash` - Hashed secret key (SHA256)
- `status` - Volume status (active, inactive, error, deleting)
- `created_at` - Creation timestamp
- `updated_at` - Last update timestamp
- `additional_info` - Extra metadata (JSON/text)

**Indexes:**
- `idx_remote_volume_owner_id` - Fast lookups by owner
- `idx_remote_volume_name` - Uniqueness checks
- `idx_remote_volume_status` - Filter by status
- `idx_remote_volume_created_at` - Time-based queries

**Constraints:**
- `chk_metadata_store_source` - Valid source types
- `chk_status` - Valid status values

### V2__add_remote_volume_metrics.sql

Adds usage tracking columns:
- `last_accessed_at` - Last access timestamp
- `access_count` - Total access count
- `storage_used_bytes` - Current storage usage
- `object_count` - Number of objects

## How Migrations Work

### Automatic Execution

Migrations run automatically when `RemoteVolumeCatalog` is initialized:

```java
RemoteVolumeCatalog catalog = new RemoteVolumeCatalog(configuration);
// Flyway migrations run here automatically
```

### Migration Process

1. **First Run**: Flyway creates a `remote_volume_schema_history` table to track applied migrations
2. **Subsequent Runs**: Flyway checks which migrations have been applied and runs only new ones
3. **Checksums**: Each migration is checksummed to detect modifications
4. **Ordering**: Migrations are applied in version order (V1, V2, V3, etc.)

### Schema History Table

Flyway tracks migrations in `remote_volume_schema_history`:

```sql
SELECT * FROM remote_volume_schema_history;
```

| Column | Description |
|--------|-------------|
| installed_rank | Order of execution |
| version | Migration version |
| description | Migration description |
| type | Migration type (SQL, JDBC, etc.) |
| script | Migration filename |
| checksum | File checksum for validation |
| installed_by | Database user who ran it |
| installed_on | Execution timestamp |
| execution_time | Time taken (ms) |
| success | Whether it succeeded |

## Creating New Migrations

### Step 1: Create Migration File

Create a new file in `db/migration/` with the next version number:

```bash
cd hadoop-ozone/admin-gateway/src/main/resources/db/migration/
touch V3__add_quota_limits.sql
```

### Step 2: Write Migration SQL

```sql
-- Add quota columns to remote_volume_catalog
ALTER TABLE remote_volume_catalog
    ADD COLUMN storage_quota_bytes BIGINT DEFAULT 0,
    ADD COLUMN object_quota_count BIGINT DEFAULT 0;

-- Add index for quota queries
CREATE INDEX IF NOT EXISTS idx_remote_volume_quota
    ON remote_volume_catalog (storage_quota_bytes);

-- Add comments
COMMENT ON COLUMN remote_volume_catalog.storage_quota_bytes IS
    'Maximum storage quota in bytes (0 = unlimited)';
```

### Step 3: Test Migration

The migration will run automatically on next startup. Test it in development:

```bash
# Start admin-gateway
# Check logs for: "Applied X Flyway migration(s) to remote volume catalog"
```

### Step 4: Verify Schema

```sql
-- Connect to catalog database
psql -h localhost -p 5432 -U postgres -d ozone_catalog

-- Verify table structure
\d remote_volume_catalog

-- Check migration history
SELECT version, description, installed_on, success
FROM remote_volume_schema_history
ORDER BY installed_rank;
```

## Configuration

Database connection configured in `ozone-site.xml`:

```xml
<!-- Catalog Database Host -->
<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.host</name>
  <value>localhost</value>
</property>

<!-- Catalog Database Port -->
<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.port</name>
  <value>5432</value>
</property>

<!-- Catalog Database Name -->
<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.name</name>
  <value>ozone_catalog</value>
</property>

<!-- Catalog Database Username -->
<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.username</name>
  <value>postgres</value>
</property>

<!-- Catalog Database Password -->
<property>
  <name>ozone.admin.gateway.juicefs.catalog.database.password</name>
  <value>your_password</value>
</property>
```

## Flyway Configuration

The following Flyway settings are configured in `RemoteVolumeCatalog.java`:

```java
Flyway flyway = Flyway.configure()
    .dataSource(jdbcUrl, dbUser, dbPassword)
    .locations("classpath:db/migration")         // Migration location
    .baselineOnMigrate(true)                     // Baseline existing DBs
    .baselineVersion("0")                        // Baseline version
    .table("remote_volume_schema_history")       // History table name
    .load();
```

## Common Operations

### Check Migration Status

```java
Flyway flyway = Flyway.configure()
    .dataSource(jdbcUrl, dbUser, dbPassword)
    .load();

MigrationInfoService info = flyway.info();
for (MigrationInfo migration : info.all()) {
    System.out.println(migration.getVersion() + " - " +
                       migration.getDescription() + " - " +
                       migration.getState());
}
```

### Manually Run Migrations (CLI)

Use Flyway CLI for manual migration control:

```bash
# Install Flyway CLI
wget https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/9.22.3/flyway-commandline-9.22.3.tar.gz
tar -xzf flyway-commandline-9.22.3.tar.gz

# Configure connection
export FLYWAY_URL=jdbc:postgresql://localhost:5432/ozone_catalog
export FLYWAY_USER=postgres
export FLYWAY_PASSWORD=your_password
export FLYWAY_LOCATIONS=filesystem:./db/migration

# Run migrations
./flyway migrate

# Check status
./flyway info

# Validate checksums
./flyway validate
```

### Repair Failed Migrations

If a migration fails:

```bash
# Mark failed migration as fixed (use with caution)
./flyway repair

# Or via Java
Flyway flyway = Flyway.configure()
    .dataSource(jdbcUrl, dbUser, dbPassword)
    .load();
flyway.repair();
```

## Best Practices

### 1. Never Modify Applied Migrations
Once a migration is applied (especially in production), **never modify it**. Create a new migration instead.

❌ **Bad:**
```sql
-- Modifying V1__create_table.sql after it's been applied
ALTER TABLE my_table ADD COLUMN new_col VARCHAR(255);
```

✅ **Good:**
```sql
-- Create V3__add_new_column.sql
ALTER TABLE my_table ADD COLUMN new_col VARCHAR(255);
```

### 2. Make Migrations Idempotent
Use `IF NOT EXISTS` and `IF EXISTS` for safety:

```sql
-- Good practice
CREATE TABLE IF NOT EXISTS my_table (...);
CREATE INDEX IF NOT EXISTS idx_name ON my_table (column);
ALTER TABLE my_table DROP COLUMN IF EXISTS old_column;
```

### 3. Test Migrations Thoroughly
- Test on a copy of production data
- Test rollback scenarios (though Flyway doesn't auto-rollback)
- Verify performance on large datasets

### 4. Keep Migrations Small
One logical change per migration makes debugging easier:

❌ **Bad:** V5__update_everything.sql (creates tables, adds columns, modifies indexes)
✅ **Good:**
- V5__add_user_table.sql
- V6__add_user_indexes.sql
- V7__modify_permissions.sql

### 5. Document Complex Migrations
Add comments explaining non-obvious changes:

```sql
-- V8__optimize_owner_queries.sql
-- Background: Owner queries are slow on large datasets (>1M rows)
-- Solution: Add composite index on (owner_id, status, created_at)

CREATE INDEX idx_owner_status_created
    ON remote_volume_catalog (owner_id, status, created_at);
```

## Troubleshooting

### Migration Checksum Mismatch

**Error:** "Migration checksum mismatch"

**Cause:** Migration file was modified after being applied

**Solution:**
```bash
# Option 1: Repair (updates checksum, use only if you know what changed)
flyway repair

# Option 2: Create new migration to fix the issue
# Create V4__fix_previous_migration.sql
```

### Baseline Existing Database

If you have an existing database without Flyway:

```java
Flyway flyway = Flyway.configure()
    .dataSource(jdbcUrl, dbUser, dbPassword)
    .baselineOnMigrate(true)
    .baselineVersion("1")  // Mark V1 as already applied
    .load();
flyway.baseline();
```

### Connection Issues

**Error:** "Unable to obtain connection from database"

**Check:**
1. Database is running: `pg_isready -h localhost -p 5432`
2. Credentials are correct
3. Network connectivity
4. Database exists: `psql -l`

## Migration Testing

### Unit Testing

```java
@Test
public void testMigrations() {
    Flyway flyway = Flyway.configure()
        .dataSource(jdbcUrl, dbUser, dbPassword)
        .locations("classpath:db/migration")
        .cleanDisabled(false)  // Allow clean for testing
        .load();

    // Clean database
    flyway.clean();

    // Run migrations
    MigrateResult result = flyway.migrate();

    // Verify
    assertEquals(2, result.migrationsExecuted);

    // Validate
    flyway.validate();
}
```

### Integration Testing

Test against real PostgreSQL instance using Docker:

```bash
# Start PostgreSQL for testing
docker run --name test-postgres \
    -e POSTGRES_PASSWORD=test \
    -e POSTGRES_DB=ozone_catalog \
    -p 5432:5432 \
    -d postgres:15

# Run tests
mvn test -pl hadoop-ozone/admin-gateway

# Cleanup
docker stop test-postgres
docker rm test-postgres
```

## Further Reading

- [Flyway Documentation](https://flywaydb.org/documentation/)
- [Flyway Concepts](https://flywaydb.org/documentation/concepts/)
- [SQL Migration Best Practices](https://flywaydb.org/documentation/migrations#sql-based-migrations)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
