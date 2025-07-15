#!/usr/bin/env python3
"""
Test script to demonstrate the Iceberg schema creation fix
"""

import boto3
import time
from botocore.client import Config

# Configure S3 client for Ozone
s3_client = boto3.client(
    's3',
    endpoint_url='http://localhost:9878',
    aws_access_key_id='hadoop',
    aws_secret_access_key='hadoop',
    config=Config(signature_version='s3v4')
)

bucket_name = 'iceberg-test'

print("=== Testing Iceberg Schema Creation Fix ===\n")

# Step 1: Create test bucket
print("1. Creating test bucket...")
try:
    s3_client.create_bucket(Bucket=bucket_name)
    print(f"✓ Created bucket: {bucket_name}")
except Exception as e:
    if 'BucketAlreadyExists' in str(e):
        print(f"✓ Bucket already exists: {bucket_name}")
    else:
        print(f"✗ Error: {e}")

# Step 2: Simulate current behavior (creates file)
print("\n2. Simulating current Trino behavior (creates file)...")
schema_location = 'test_schema_broken'
try:
    # Current behavior - creates without trailing slash
    s3_client.put_object(Bucket=bucket_name, Key=schema_location, Body=b'')
    print(f"✓ Created '{schema_location}' as a file (current broken behavior)")
except Exception as e:
    print(f"✗ Error: {e}")

# Try to create table metadata in broken schema
print("\n3. Trying to create table metadata in broken schema...")
try:
    s3_client.put_object(
        Bucket=bucket_name, 
        Key=f'{schema_location}/metadata/v1.metadata.json',
        Body=b'{"table": "metadata"}'
    )
    print("✗ Unexpected success - should have failed!")
except Exception as e:
    print(f"✓ Expected failure: {e}")

# Step 3: Simulate fixed behavior (creates directory)
print("\n4. Simulating fixed Trino behavior (creates directory)...")
schema_location_fixed = 'test_schema_fixed/'
try:
    # Fixed behavior - creates with trailing slash
    s3_client.put_object(Bucket=bucket_name, Key=schema_location_fixed, Body=b'')
    print(f"✓ Created '{schema_location_fixed}' as a directory (fixed behavior)")
except Exception as e:
    print(f"✗ Error: {e}")

# Try to create table metadata in fixed schema
print("\n5. Creating table metadata in fixed schema...")
try:
    # Create metadata directory
    s3_client.put_object(
        Bucket=bucket_name,
        Key=f'{schema_location_fixed}metadata/',
        Body=b''
    )
    
    # Create table metadata file
    metadata = {
        "format-version": 2,
        "table-uuid": "test-uuid",
        "location": f"s3://{bucket_name}/{schema_location_fixed}test_table",
        "schemas": [],
        "current-schema-id": 0
    }
    
    s3_client.put_object(
        Bucket=bucket_name,
        Key=f'{schema_location_fixed}test_table/metadata/v1.metadata.json',
        Body=str(metadata).encode('utf-8')
    )
    print("✓ Successfully created table metadata in fixed schema")
except Exception as e:
    print(f"✗ Error: {e}")

# Step 4: List objects to show the difference
print("\n6. Listing all objects to show the difference:")
response = s3_client.list_objects_v2(Bucket=bucket_name)
if 'Contents' in response:
    for obj in response['Contents']:
        obj_type = "directory" if obj['Key'].endswith('/') else "file"
        print(f"  - {obj['Key']} ({obj_type}, Size: {obj['Size']})")

print("\n=== Summary ===")
print("❌ Current behavior: Schema created as file → Table creation fails")
print("✅ Fixed behavior: Schema created as directory → Table creation succeeds")
print("\nThe fix ensures that schema locations are created with trailing slashes,")
print("making them compatible with S3 stores that enforce filesystem semantics.")