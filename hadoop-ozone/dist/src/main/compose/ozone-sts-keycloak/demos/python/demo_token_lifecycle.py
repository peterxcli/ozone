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
Demo of token lifecycle management:
- Token expiration
- Token refresh
- Credential renewal
- Session management
"""

import time
from datetime import datetime, timedelta
from typing import Optional
import boto3
from botocore.exceptions import ClientError

from keycloak_client import KeycloakClient
from sts_client import STSClient, STSCredentials


# Configuration
KEYCLOAK_URL = 'http://localhost:8080'
KEYCLOAK_REALM = 'ozone'
KEYCLOAK_CLIENT_ID = 'ozone-s3'

STS_ENDPOINT = 'http://localhost:9878'
S3_ENDPOINT = 'http://localhost:9878'


class TokenLifecycleManager:
    """Manages token lifecycle and automatic renewal"""
    
    def __init__(self, username: str, password: str):
        self.username = username
        self.password = password
        
        self.keycloak_client = KeycloakClient(
            KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_CLIENT_ID
        )
        self.sts_client = STSClient(STS_ENDPOINT)
        
        # Current tokens
        self.oidc_token: Optional[str] = None
        self.oidc_token_expires_at: Optional[datetime] = None
        self.refresh_token: Optional[str] = None
        
        # Current S3 credentials
        self.s3_credentials: Optional[STSCredentials] = None
        self.s3_client: Optional[boto3.client] = None
    
    def print_status(self, message: str):
        """Print timestamped status message"""
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print(f"[{timestamp}] {message}")
    
    def authenticate(self):
        """Initial authentication with Keycloak"""
        self.print_status(f"Authenticating as {self.username}...")
        
        token_data = self.keycloak_client.authenticate(
            self.username, self.password
        )
        
        self.oidc_token = token_data['access_token']
        self.refresh_token = token_data['refresh_token']
        
        # Calculate expiration
        expires_in = token_data.get('expires_in', 300)
        self.oidc_token_expires_at = datetime.now() + timedelta(seconds=expires_in)
        
        self.print_status(f"✓ Authenticated successfully")
        self.print_status(f"  OIDC token expires at: {self.oidc_token_expires_at}")
    
    def refresh_oidc_token(self):
        """Refresh OIDC token using refresh token"""
        self.print_status("Refreshing OIDC token...")
        
        if not self.refresh_token:
            self.print_status("  No refresh token available, re-authenticating")
            self.authenticate()
            return
        
        try:
            token_data = self.keycloak_client.refresh_token(self.refresh_token)
            
            self.oidc_token = token_data['access_token']
            self.refresh_token = token_data.get('refresh_token', self.refresh_token)
            
            expires_in = token_data.get('expires_in', 300)
            self.oidc_token_expires_at = datetime.now() + timedelta(seconds=expires_in)
            
            self.print_status("✓ OIDC token refreshed")
            self.print_status(f"  New expiration: {self.oidc_token_expires_at}")
            
        except Exception as e:
            self.print_status(f"✗ Token refresh failed: {e}")
            self.print_status("  Re-authenticating...")
            self.authenticate()
    
    def get_s3_credentials(self):
        """Get new S3 credentials from STS"""
        self.print_status("Getting S3 credentials from STS...")
        
        # Ensure OIDC token is valid
        if not self.oidc_token or datetime.now() >= self.oidc_token_expires_at:
            self.print_status("  OIDC token expired, refreshing...")
            self.refresh_oidc_token()
        
        try:
            self.s3_credentials = self.sts_client.assume_role_with_web_identity(
                web_identity_token=self.oidc_token,
                role_arn=f"arn:ozone:iam::{self.username}-role",
                role_session_name=f"{self.username}-session-{int(time.time())}",
                duration_seconds=300  # 5 minutes for demo
            )
            
            # Create new S3 client
            self.s3_client = boto3.client(
                's3',
                endpoint_url=S3_ENDPOINT,
                **self.s3_credentials.to_aws_config()
            )
            
            self.print_status("✓ S3 credentials obtained")
            self.print_status(f"  Access Key: {self.s3_credentials.access_key_id}")
            self.print_status(f"  Expires at: {self.s3_credentials.expiration}")
            
        except Exception as e:
            self.print_status(f"✗ Failed to get S3 credentials: {e}")
            raise
    
    def ensure_valid_credentials(self):
        """Ensure S3 credentials are valid, refresh if needed"""
        if not self.s3_credentials or self.s3_credentials.is_expired():
            self.print_status("S3 credentials expired or missing, renewing...")
            self.get_s3_credentials()
    
    def perform_s3_operation(self, operation_name: str):
        """Perform an S3 operation with automatic credential renewal"""
        self.print_status(f"Performing S3 operation: {operation_name}")
        
        try:
            self.ensure_valid_credentials()
            
            if operation_name == "list_buckets":
                response = self.s3_client.list_buckets()
                buckets = [b['Name'] for b in response.get('Buckets', [])]
                self.print_status(f"  ✓ Listed {len(buckets)} buckets")
                
            elif operation_name == "upload_object":
                bucket = 'shared-bucket'
                key = f'{self.username}-lifecycle-test.txt'
                content = f"Test from {self.username} at {datetime.now()}"
                self.s3_client.put_object(Bucket=bucket, Key=key, Body=content.encode())
                self.print_status(f"  ✓ Uploaded object to {bucket}/{key}")
                
            elif operation_name == "download_object":
                bucket = 'shared-bucket'
                key = f'{self.username}-lifecycle-test.txt'
                response = self.s3_client.get_object(Bucket=bucket, Key=key)
                content = response['Body'].read().decode()
                self.print_status(f"  ✓ Downloaded object from {bucket}/{key}")
                
            else:
                self.print_status(f"  Unknown operation: {operation_name}")
                
        except ClientError as e:
            error_code = e.response['Error']['Code']
            if error_code in ['ExpiredToken', 'InvalidToken', 'AccessDenied']:
                self.print_status(f"  Credentials invalid ({error_code}), renewing...")
                self.get_s3_credentials()
                # Retry operation
                self.perform_s3_operation(operation_name)
            else:
                self.print_status(f"  ✗ S3 operation failed: {error_code}")
        
        except Exception as e:
            self.print_status(f"  ✗ Operation failed: {e}")


def demo_basic_lifecycle():
    """Demo 1: Basic token lifecycle"""
    print("\n" + "=" * 70)
    print("Demo 1: Basic Token Lifecycle".center(70))
    print("=" * 70 + "\n")
    
    manager = TokenLifecycleManager('developer', 'dev123')
    
    # Step 1: Initial authentication
    manager.authenticate()
    time.sleep(2)
    
    # Step 2: Get S3 credentials
    manager.get_s3_credentials()
    time.sleep(2)
    
    # Step 3: Perform S3 operations
    manager.perform_s3_operation('list_buckets')
    time.sleep(2)
    manager.perform_s3_operation('upload_object')
    time.sleep(2)
    manager.perform_s3_operation('download_object')
    
    print("\n✓ Demo 1 complete")


def demo_automatic_renewal():
    """Demo 2: Automatic credential renewal"""
    print("\n" + "=" * 70)
    print("Demo 2: Automatic Credential Renewal".center(70))
    print("=" * 70 + "\n")
    
    manager = TokenLifecycleManager('admin', 'admin123')
    
    # Initial setup
    manager.authenticate()
    manager.get_s3_credentials()
    
    # Simulate long-running session with operations over time
    print("\nSimulating long-running session (will auto-renew credentials)...\n")
    
    for i in range(5):
        print(f"\n--- Iteration {i+1} ---")
        manager.perform_s3_operation('list_buckets')
        
        # Check credential status
        if manager.s3_credentials:
            is_expired = manager.s3_credentials.is_expired()
            manager.print_status(f"Credentials expired: {is_expired}")
        
        # Wait before next iteration
        if i < 4:
            wait_time = 30  # Wait 30 seconds between operations
            manager.print_status(f"Waiting {wait_time} seconds...")
            time.sleep(wait_time)
    
    print("\n✓ Demo 2 complete")


def demo_token_expiration_handling():
    """Demo 3: Handling token expiration gracefully"""
    print("\n" + "=" * 70)
    print("Demo 3: Token Expiration Handling".center(70))
    print("=" * 70 + "\n")
    
    manager = TokenLifecycleManager('auditor', 'audit123')
    
    # Initial setup
    manager.authenticate()
    manager.get_s3_credentials()
    
    # Check initial status
    manager.print_status("Initial credential status:")
    manager.print_status(f"  OIDC token expires: {manager.oidc_token_expires_at}")
    manager.print_status(f"  S3 creds expire: {manager.s3_credentials.expiration}")
    
    # Perform operation
    manager.perform_s3_operation('list_buckets')
    
    # Simulate credentials expiring
    print("\n--- Simulating credential expiration ---\n")
    manager.s3_credentials.expiration = datetime.now().isoformat()
    manager.print_status("Credentials artificially expired")
    
    # Try operation - should auto-renew
    manager.perform_s3_operation('list_buckets')
    
    print("\n✓ Demo 3 complete")


def main():
    """Run all lifecycle demos"""
    print("\n" + "=" * 70)
    print("Ozone STS Token Lifecycle Management Demo".center(70))
    print("=" * 70)
    
    print("\nThis demo shows:")
    print("  • Basic token lifecycle")
    print("  • Automatic credential renewal")
    print("  • Graceful handling of expired tokens")
    print("  • Session management best practices")
    
    try:
        # Run demos
        demo_basic_lifecycle()
        
        # Uncomment to run additional demos (they take time)
        # demo_automatic_renewal()
        # demo_token_expiration_handling()
        
        print("\n" + "=" * 70)
        print("All Demos Complete!".center(70))
        print("=" * 70)
        
        print("\nKey Takeaways:")
        print("  • Always check token expiration before operations")
        print("  • Use refresh tokens to avoid re-authentication")
        print("  • Implement automatic renewal for long-running sessions")
        print("  • Handle expiration errors gracefully with retry logic")
        
    except KeyboardInterrupt:
        print("\n\nDemo interrupted by user")
    except Exception as e:
        print(f"\n\nDemo failed: {e}")
        import traceback
        traceback.print_exc()


if __name__ == '__main__':
    main()

