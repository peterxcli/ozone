#!/usr/bin/env python3
"""
Test script to demonstrate the file vs directory creation issue with Ozone S3 Gateway
"""

import boto3
from botocore.client import Config

# Configure S3 client for Ozone
s3_client = boto3.client(
    's3',
    endpoint_url='http://localhost:9878',
    aws_access_key_id='hadoop',
    aws_secret_access_key='hadoop',
    config=Config(signature_version='s3v4')
)

bucket_name = 'testbucket'

# Test 1: Create a key without trailing slash (should create a file)
print("Test 1: Creating key without trailing slash")
try:
    s3_client.put_object(Bucket=bucket_name, Key='schema1', Body=b'')
    print("✓ Created 'schema1' - expected to be a file")
except Exception as e:
    print(f"✗ Error: {e}")

# Test 2: Create a key with trailing slash (should create a directory)
print("\nTest 2: Creating key with trailing slash")
try:
    s3_client.put_object(Bucket=bucket_name, Key='schema2/', Body=b'')
    print("✓ Created 'schema2/' - expected to be a directory")
except Exception as e:
    print(f"✗ Error: {e}")

# Test 3: Try to create a file inside schema1 (should fail since schema1 is a file)
print("\nTest 3: Trying to create file inside schema1")
try:
    s3_client.put_object(Bucket=bucket_name, Key='schema1/table.metadata', Body=b'test')
    print("✓ Created 'schema1/table.metadata' - unexpected success!")
except Exception as e:
    print(f"✗ Expected error: {e}")

# Test 4: Try to create a file inside schema2 (should succeed since schema2 is a directory)
print("\nTest 4: Creating file inside schema2")
try:
    s3_client.put_object(Bucket=bucket_name, Key='schema2/table.metadata', Body=b'test')
    print("✓ Created 'schema2/table.metadata' - expected success")
except Exception as e:
    print(f"✗ Unexpected error: {e}")

# List objects to see what was created
print("\nListing all objects:")
response = s3_client.list_objects_v2(Bucket=bucket_name)
if 'Contents' in response:
    for obj in response['Contents']:
        print(f"  - {obj['Key']} (Size: {obj['Size']})")
else:
    print("  No objects found")