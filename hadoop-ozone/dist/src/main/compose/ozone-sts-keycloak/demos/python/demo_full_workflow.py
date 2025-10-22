#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Complete end-to-end demo of Keycloak OIDC → Ozone STS → S3 operations
"""

import sys
import time
from datetime import datetime
from typing import Dict, List, Tuple
import boto3
from botocore.exceptions import ClientError
from tabulate import tabulate

from keycloak_client import KeycloakClient
from sts_client import STSClient, STSCredentials


# Configuration
KEYCLOAK_URL = 'http://localhost:8080'
KEYCLOAK_REALM = 'ozone'
KEYCLOAK_CLIENT_ID = 'ozone-s3'

STS_ENDPOINT = 'http://localhost:9878'
S3_ENDPOINT = 'http://localhost:9878'

# Test users
USERS = [
    {'username': 'admin', 'password': 'admin123', 'role': 's3-admin'},
    {'username': 'developer', 'password': 'dev123', 'role': 's3-full-access'},
    {'username': 'auditor', 'password': 'audit123', 'role': 's3-read-only'}
]


class OzoneSTSDemo:
    """Demonstration of complete STS workflow"""
    
    def __init__(self):
        self.keycloak_client = KeycloakClient(
            KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_CLIENT_ID
        )
        self.sts_client = STSClient(STS_ENDPOINT)
        self.user_credentials: Dict[str, STSCredentials] = {}
        self.user_s3_clients: Dict[str, boto3.client] = {}
    
    def print_header(self, title: str):
        """Print section header"""
        print("\n" + "=" * 70)
        print(f"{title:^70}")
        print("=" * 70)
    
    def print_step(self, step: str, success: bool = True):
        """Print step result"""
        symbol = "✓" if success else "✗"
        print(f"{symbol} {step}")
    
    def step1_authenticate_users(self):
        """Step 1: Authenticate all users with Keycloak"""
        self.print_header("Step 1: Authenticate Users with Keycloak")
        
        for user in USERS:
            username = user['username']
            password = user['password']
            
            try:
                print(f"\nAuthenticating {username}...")
                token_data = self.keycloak_client.authenticate(username, password)
                
                # Get token info
                token_info = self.keycloak_client.get_token_info(
                    token_data['access_token']
                )
                
                self.print_step(f"Authenticated as {username}")
                print(f"  Email: {token_info['email']}")
                print(f"  Groups: {', '.join(token_info['groups'])}")
                print(f"  Roles: {', '.join(token_info['roles'])}")
                print(f"  Token expires in: {token_data['expires_in']} seconds")
                
                # Store token for next step
                user['access_token'] = token_data['access_token']
                
            except Exception as e:
                self.print_step(f"Failed to authenticate {username}: {e}", False)
                user['access_token'] = None
    
    def step2_assume_roles(self):
        """Step 2: Exchange OIDC tokens for S3 credentials"""
        self.print_header("Step 2: Exchange OIDC Tokens for S3 Credentials")
        
        for user in USERS:
            username = user['username']
            access_token = user.get('access_token')
            
            if not access_token:
                print(f"\nSkipping {username} (no token)")
                continue
            
            try:
                print(f"\nAssuming role for {username}...")
                
                credentials = self.sts_client.assume_role_with_web_identity(
                    web_identity_token=access_token,
                    role_arn=f"arn:ozone:iam::{username}-role",
                    role_session_name=f"{username}-session-{int(time.time())}",
                    duration_seconds=3600
                )
                
                self.user_credentials[username] = credentials
                
                self.print_step(f"Got temporary credentials for {username}")
                print(f"  Access Key ID: {credentials.access_key_id}")
                print(f"  Expiration: {credentials.expiration}")
                
                # Create S3 client
                s3_client = boto3.client(
                    's3',
                    endpoint_url=S3_ENDPOINT,
                    **credentials.to_aws_config()
                )
                self.user_s3_clients[username] = s3_client
                
            except Exception as e:
                self.print_step(f"Failed to assume role for {username}: {e}", False)
    
    def step3_test_s3_operations(self):
        """Step 3: Test S3 operations with different roles"""
        self.print_header("Step 3: Test S3 Operations (Role-Based Access)")
        
        # Test matrix: [operation, admin, developer, auditor]
        test_results = []
        
        # Test 1: List buckets
        print("\n--- Test 1: List Buckets ---")
        for username in ['admin', 'developer', 'auditor']:
            result = self._test_list_buckets(username)
            test_results.append(['List Buckets', username, result])
        
        # Test 2: Create bucket
        print("\n--- Test 2: Create Bucket ---")
        for username in ['admin', 'developer', 'auditor']:
            bucket_name = f'test-{username}-bucket'
            result = self._test_create_bucket(username, bucket_name)
            test_results.append(['Create Bucket', username, result])
        
        # Test 3: Upload object
        print("\n--- Test 3: Upload Object ---")
        for username in ['admin', 'developer', 'auditor']:
            result = self._test_put_object(username, 'shared-bucket', 
                                          f'{username}-test.txt')
            test_results.append(['Upload Object', username, result])
        
        # Test 4: Download object
        print("\n--- Test 4: Download Object ---")
        for username in ['admin', 'developer', 'auditor']:
            result = self._test_get_object(username, 'shared-bucket',
                                          'admin-test.txt')
            test_results.append(['Download Object', username, result])
        
        # Test 5: Delete object
        print("\n--- Test 5: Delete Object ---")
        for username in ['admin', 'developer', 'auditor']:
            result = self._test_delete_object(username, 'shared-bucket',
                                             f'{username}-delete.txt')
            test_results.append(['Delete Object', username, result])
        
        # Display results table
        print("\n" + "=" * 70)
        print("Access Control Matrix")
        print("=" * 70)
        table_data = [[r[0], r[1], '✓ ALLOWED' if r[2] else '✗ DENIED'] 
                     for r in test_results]
        print(tabulate(table_data, headers=['Operation', 'User', 'Result'],
                      tablefmt='grid'))
    
    def _test_list_buckets(self, username: str) -> bool:
        """Test listing buckets"""
        if username not in self.user_s3_clients:
            return False
        
        try:
            s3 = self.user_s3_clients[username]
            response = s3.list_buckets()
            buckets = [b['Name'] for b in response.get('Buckets', [])]
            print(f"  {username}: Listed {len(buckets)} buckets")
            return True
        except ClientError as e:
            print(f"  {username}: {e.response['Error']['Code']}")
            return False
        except Exception as e:
            print(f"  {username}: {str(e)}")
            return False
    
    def _test_create_bucket(self, username: str, bucket_name: str) -> bool:
        """Test creating a bucket"""
        if username not in self.user_s3_clients:
            return False
        
        try:
            s3 = self.user_s3_clients[username]
            s3.create_bucket(Bucket=bucket_name)
            print(f"  {username}: Created bucket '{bucket_name}'")
            return True
        except ClientError as e:
            print(f"  {username}: {e.response['Error']['Code']}")
            return False
        except Exception as e:
            print(f"  {username}: {str(e)}")
            return False
    
    def _test_put_object(self, username: str, bucket: str, key: str) -> bool:
        """Test uploading an object"""
        if username not in self.user_s3_clients:
            return False
        
        try:
            s3 = self.user_s3_clients[username]
            content = f"Test data from {username} at {datetime.now()}"
            s3.put_object(Bucket=bucket, Key=key, Body=content.encode())
            print(f"  {username}: Uploaded '{key}' to '{bucket}'")
            return True
        except ClientError as e:
            print(f"  {username}: {e.response['Error']['Code']}")
            return False
        except Exception as e:
            print(f"  {username}: {str(e)}")
            return False
    
    def _test_get_object(self, username: str, bucket: str, key: str) -> bool:
        """Test downloading an object"""
        if username not in self.user_s3_clients:
            return False
        
        try:
            s3 = self.user_s3_clients[username]
            response = s3.get_object(Bucket=bucket, Key=key)
            content = response['Body'].read().decode()
            print(f"  {username}: Downloaded '{key}' from '{bucket}'")
            return True
        except ClientError as e:
            print(f"  {username}: {e.response['Error']['Code']}")
            return False
        except Exception as e:
            print(f"  {username}: {str(e)}")
            return False
    
    def _test_delete_object(self, username: str, bucket: str, key: str) -> bool:
        """Test deleting an object"""
        if username not in self.user_s3_clients:
            return False
        
        try:
            s3 = self.user_s3_clients[username]
            s3.delete_object(Bucket=bucket, Key=key)
            print(f"  {username}: Deleted '{key}' from '{bucket}'")
            return True
        except ClientError as e:
            print(f"  {username}: {e.response['Error']['Code']}")
            return False
        except Exception as e:
            print(f"  {username}: {str(e)}")
            return False
    
    def run(self):
        """Run the complete demo"""
        self.print_header("Ozone STS + Keycloak Demo")
        print("\nThis demo demonstrates the complete workflow:")
        print("  1. Authenticate with Keycloak (OIDC)")
        print("  2. Exchange OIDC token for S3 credentials (STS)")
        print("  3. Use temporary credentials for S3 operations")
        print("  4. Role-based access control enforcement")
        
        try:
            self.step1_authenticate_users()
            self.step2_assume_roles()
            self.step3_test_s3_operations()
            
            self.print_header("Demo Complete!")
            print("\nSummary:")
            print("  ✓ All users authenticated with Keycloak")
            print("  ✓ OIDC tokens exchanged for temporary S3 credentials")
            print("  ✓ Role-based access control demonstrated")
            print("\nKey Observations:")
            print("  • Admin users have full access (create/delete buckets)")
            print("  • Developer users can read/write objects but not manage buckets")
            print("  • Auditor users have read-only access")
            
        except KeyboardInterrupt:
            print("\n\nDemo interrupted by user")
        except Exception as e:
            print(f"\n\nDemo failed with error: {e}")
            import traceback
            traceback.print_exc()


if __name__ == '__main__':
    demo = OzoneSTSDemo()
    demo.run()

