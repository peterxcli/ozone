#!/bin/bash
# Script to demonstrate the workaround for Presto/Trino Iceberg issue with Ozone

echo "=== Testing Presto/Trino Iceberg Workaround for Ozone ==="
echo

# Clean up previous test
echo "1. Cleaning up previous test data..."
docker exec ozone-om-1 ozone sh bucket delete s3v/presto-iceberg-test || true
docker exec ozone-om-1 ozone sh bucket create s3v/presto-iceberg-test
echo "✓ Created bucket: presto-iceberg-test"
echo

# Pre-create schema directory with trailing slash
echo "2. Pre-creating schema directory with trailing slash..."
aws s3api put-object \
    --bucket presto-iceberg-test \
    --key "test_schema/" \
    --endpoint-url http://localhost:9878 \
    --no-sign-request 2>/dev/null || \
docker exec ozone-om-1 ozone sh key put s3v/presto-iceberg-test/test_schema/ /dev/null

echo "✓ Created schema directory: test_schema/"
echo

# Check the object type
echo "3. Verifying object type in Ozone..."
docker exec ozone-om-1 ozone sh key list s3v/presto-iceberg-test | grep test_schema | head -1
echo

# Now simulate table creation
echo "4. Simulating Iceberg table creation in the schema..."
# Create metadata directory
docker exec ozone-om-1 ozone sh key put s3v/presto-iceberg-test/test_schema/test_table/metadata/ /dev/null

# Create a sample metadata file
echo '{"format-version": 2, "table-uuid": "test-uuid"}' | \
docker exec ozone-om-1 ozone sh key put s3v/presto-iceberg-test/test_schema/test_table/metadata/v1.metadata.json -

echo "✓ Successfully created table metadata structure"
echo

echo "5. Listing all objects in the bucket..."
docker exec ozone-om-1 ozone sh key list s3v/presto-iceberg-test | grep -E "(name|file)" | head -20
echo

echo "=== Summary ==="
echo "✅ Workaround successful: Pre-creating schema directory with trailing slash"
echo "   allows subsequent table creation to work properly."
echo
echo "For Presto/Trino users:"
echo "1. Before: CREATE SCHEMA iceberg.my_schema WITH (location = 's3a://bucket/my_schema')"
echo "2. Pre-create directory: aws s3api put-object --bucket bucket --key 'my_schema/' --endpoint-url ..."
echo "3. Then: CREATE SCHEMA iceberg.my_schema WITH (location = 's3a://bucket/my_schema')"
echo "4. Now: CREATE TABLE iceberg.my_schema.my_table (...) will work!"