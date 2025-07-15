# PrestoDB Iceberg Connector Fix for Apache Ozone S3 Gateway

## Issue Summary

When using PrestoDB's Iceberg connector with Apache Ozone S3 Gateway, creating schemas fails because:
1. Ozone has `ozone.om.enable.filesystem.paths` enabled, which enforces filesystem semantics
2. PrestoDB creates schema locations without trailing slashes, making them file objects
3. Subsequent table creation fails because Iceberg cannot create metadata files inside file objects

## The Fix

### Patch File: `prestodb-iceberg-fix.patch`

```patch
From: Fix Iceberg schema creation to ensure directory is created with trailing slash for Ozone compatibility
Date: 2025-07-15
Subject: [PATCH] Fix Iceberg schema creation for S3 stores with filesystem semantics

When creating an Iceberg schema with a location property, the connector currently
only validates that the location path is valid but doesn't ensure it's created as a
directory. This causes issues with S3-compatible stores like Apache Ozone that enforce
filesystem semantics, where directories must be created with trailing slashes.

This patch modifies the schema creation logic to:
1. Ensure the location ends with a trailing slash (required for S3 directory semantics)
2. Create the directory if it doesn't exist
3. Provide better error messages when directory creation fails

This fix ensures compatibility with Apache Ozone and other S3-compatible stores that
distinguish between files and directories based on trailing slashes.

---
 .../presto/iceberg/IcebergHiveMetadata.java        | 24 ++++++++++++++++++++---
 1 file changed, 21 insertions(+), 3 deletions(-)

diff --git a/presto-iceberg/src/main/java/com/facebook/presto/iceberg/IcebergHiveMetadata.java b/presto-iceberg/src/main/java/com/facebook/presto/iceberg/IcebergHiveMetadata.java
index 1234567..abcdefg 100644
--- a/presto-iceberg/src/main/java/com/facebook/presto/iceberg/IcebergHiveMetadata.java
+++ b/presto-iceberg/src/main/java/com/facebook/presto/iceberg/IcebergHiveMetadata.java
@@ -70,6 +70,7 @@ import com.google.common.collect.ImmutableSet;
 import com.google.common.collect.Maps;
 import io.airlift.json.JsonCodec;
 import io.airlift.log.Logger;
+import org.apache.hadoop.fs.FileSystem;
 import org.apache.hadoop.fs.Path;
 import org.apache.iceberg.AppendFiles;
 import org.apache.iceberg.BaseTable;
@@ -295,11 +296,28 @@ public class IcebergHiveMetadata
     public void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties)
     {
         Optional<String> location = getLocation(properties).map(uri -> {
+            // Ensure the location ends with a slash for S3 compatibility
+            String normalizedUri = uri;
+            if (!uri.endsWith("/")) {
+                normalizedUri = uri + "/";
+            }
+            
             try {
-                hdfsEnvironment.getFileSystem(new HdfsContext(session, schemaName), new Path(uri));
+                HdfsContext hdfsContext = new HdfsContext(session, schemaName);
+                Path locationPath = new Path(normalizedUri);
+                
+                // Get the filesystem and create the directory if it doesn't exist
+                FileSystem fileSystem = hdfsEnvironment.getFileSystem(hdfsContext, locationPath);
+                if (!fileSystem.exists(locationPath)) {
+                    // Create the directory
+                    if (!fileSystem.mkdirs(locationPath)) {
+                        throw new IOException("Failed to create directory: " + normalizedUri);
+                    }
+                }
             }
             catch (IOException | IllegalArgumentException e) {
-                throw new PrestoException(INVALID_SCHEMA_PROPERTY, "Invalid location URI: " + uri, e);
+                throw new PrestoException(INVALID_SCHEMA_PROPERTY, 
+                        "Failed to create or access schema location: " + normalizedUri, e);
             }
-            return uri;
+            return normalizedUri;
         });
```

## How to Apply the Patch

1. Clone PrestoDB repository:
```bash
git clone https://github.com/prestodb/presto.git
cd presto
```

2. Apply the patch:
```bash
git apply prestodb-iceberg-fix.patch
```

3. Build PrestoDB:
```bash
./mvnw clean package -DskipTests -pl presto-iceberg -am
```

## Testing the Fix

### Prerequisites

1. Apache Ozone running with S3 Gateway on port 9878
2. PrestoDB with the patch applied

### Test Commands

```bash
# Run the test script
./test-prestodb-iceberg-ozone.sh
```

### Manual Testing

1. Start PrestoDB:
```bash
docker-compose -f docker-compose-prestodb.yaml up -d
```

2. Connect to Presto CLI:
```bash
docker exec -it presto-coordinator presto --catalog iceberg
```

3. Create a schema with location:
```sql
CREATE SCHEMA test_schema 
WITH (location = 's3a://prestodb-test/my_schema');
```

4. Create a table in the schema:
```sql
CREATE TABLE test_schema.test_table (
    id BIGINT,
    name VARCHAR
) WITH (
    format = 'PARQUET',
    partitioning = ARRAY['id']
);
```

5. Insert data:
```sql
INSERT INTO test_schema.test_table VALUES (1, 'test');
```

## Workaround (Without Patch)

If you cannot apply the patch immediately, use this workaround:

```bash
# 1. Pre-create the schema directory with trailing slash
aws s3api put-object --bucket mybucket --key "myschema/" \
  --endpoint-url http://ozone-s3-gateway:9878 \
  --no-sign-request

# 2. Then create the schema in Presto
CREATE SCHEMA iceberg.myschema 
WITH (location = 's3a://mybucket/myschema');

# 3. Now table creation will work
CREATE TABLE iceberg.myschema.mytable (
  id BIGINT,
  name VARCHAR
) WITH (format = 'PARQUET');
```

## Configuration Files

### docker-compose-prestodb.yaml
See the provided docker-compose file for running PrestoDB with Iceberg support.

### PrestoDB Configuration
- `config.properties`: Main Presto configuration
- `jvm.config`: JVM settings
- `node.properties`: Node configuration
- `catalog/iceberg.properties`: Iceberg connector configuration with Ozone S3 settings

## Key Differences from Trino

1. **Package structure**: `com.facebook.presto.iceberg` vs `io.trino.plugin.iceberg`
2. **Class names**: `IcebergHiveMetadata` vs `TrinoHiveCatalog`
3. **API differences**: Different method signatures and exception handling

## Submitting the Fix

1. Fork the PrestoDB repository
2. Create a feature branch
3. Apply the patch
4. Add unit tests to verify the behavior
5. Submit a pull request with:
   - Clear description of the issue
   - Explanation of the fix
   - Test results showing it works with Ozone

## References

- PrestoDB Repository: https://github.com/prestodb/presto
- Apache Ozone: https://ozone.apache.org/
- Issue: Iceberg schemas created as files instead of directories in S3-compatible stores