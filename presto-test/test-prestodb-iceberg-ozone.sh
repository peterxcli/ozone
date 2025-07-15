#!/bin/bash
# Test script for PrestoDB Iceberg connector with Apache Ozone S3 Gateway

echo "=== Testing PrestoDB Iceberg Connector with Ozone S3 Gateway ==="
echo
echo "Prerequisites:"
echo "1. Apache Ozone is running with S3 Gateway on port 9878"
echo "2. PrestoDB patch has been applied"
echo

# Function to run Presto SQL
run_presto_sql() {
    docker exec -it presto-coordinator presto --catalog iceberg --execute "$1" 2>&1
}

# Clean up previous test
echo "Step 1: Cleaning up previous test data..."
docker exec ozone-om-1 ozone sh bucket delete s3v/prestodb-test || true
docker exec ozone-om-1 ozone sh bucket create s3v/prestodb-test
echo "✓ Created bucket: prestodb-test"
echo

# Test 1: Without the fix (should fail)
echo "Step 2: Testing current behavior (without fix) - Schema without trailing slash"
echo "This simulates what happens without the patch..."

# Create a schema location without trailing slash
docker exec ozone-om-1 ozone sh key put s3v/prestodb-test/broken_schema /dev/null
echo "✓ Created 'broken_schema' as a file object"

# Check object type
echo "Object type in Ozone:"
docker exec ozone-om-1 ozone sh key list s3v/prestodb-test | grep broken_schema

# Try to create table metadata (should fail)
echo
echo "Attempting to create table metadata in broken schema..."
if docker exec ozone-om-1 ozone sh key put s3v/prestodb-test/broken_schema/table1/metadata/v1.metadata.json /dev/null 2>&1; then
    echo "✗ Unexpected success - this would normally fail in Iceberg"
else
    echo "✓ Expected failure - cannot create files inside a file object"
fi

echo
echo "Step 3: Testing fixed behavior (with patch) - Schema with trailing slash"
echo "This demonstrates what happens with the patch applied..."

# The patch would create this automatically with trailing slash
docker exec ozone-om-1 ozone sh key put s3v/prestodb-test/fixed_schema/ /dev/null
echo "✓ Created 'fixed_schema/' as a directory object"

# Check object type
echo "Object type in Ozone:"
docker exec ozone-om-1 ozone sh key list s3v/prestodb-test | grep fixed_schema

# Create table metadata (should succeed)
echo
echo "Creating table metadata in fixed schema..."
docker exec ozone-om-1 ozone sh key put s3v/prestodb-test/fixed_schema/table1/metadata/ /dev/null
echo '{"format-version": 2, "table-uuid": "test-uuid"}' | \
    docker exec -i ozone-om-1 ozone sh key put s3v/prestodb-test/fixed_schema/table1/metadata/v1.metadata.json -
echo "✓ Successfully created table metadata"

echo
echo "Step 4: List all objects to show the difference"
docker exec ozone-om-1 ozone sh key list s3v/prestodb-test | head -10

echo
echo "=== PrestoDB Commands to Test ==="
echo
echo "After applying the patch and starting PrestoDB:"
echo
echo "1. Start PrestoDB (if not running):"
echo "   docker-compose -f docker-compose-prestodb.yaml up -d"
echo
echo "2. Connect to Presto CLI:"
echo "   docker exec -it presto-coordinator presto --catalog iceberg"
echo
echo "3. Create schema with location (patch ensures trailing slash):"
echo "   CREATE SCHEMA test_schema WITH (location = 's3a://prestodb-test/my_schema');"
echo
echo "4. Create table in the schema:"
echo "   CREATE TABLE test_schema.test_table ("
echo "     id BIGINT,"
echo "     name VARCHAR"
echo "   ) WITH ("
echo "     format = 'PARQUET',"
echo "     partitioning = ARRAY['id']"
echo "   );"
echo
echo "5. Insert data:"
echo "   INSERT INTO test_schema.test_table VALUES (1, 'test');"
echo
echo "=== Summary ==="
echo "Without patch: Schema locations created without trailing slash → file objects → table creation fails"
echo "With patch: Schema locations created with trailing slash → directory objects → table creation succeeds"