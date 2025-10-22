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
Keycloak client for OIDC authentication
"""

import base64
import json
import time
from typing import Dict, Optional, Tuple
import requests
import jwt


class KeycloakClient:
    """Client for interacting with Keycloak OIDC provider"""
    
    def __init__(self, server_url: str, realm: str, client_id: str):
        """
        Initialize Keycloak client
        
        Args:
            server_url: Keycloak server URL (e.g., http://localhost:8080)
            realm: Keycloak realm name
            client_id: Client ID for authentication
        """
        self.server_url = server_url.rstrip('/')
        self.realm = realm
        self.client_id = client_id
        self.token_endpoint = f"{server_url}/realms/{realm}/protocol/openid-connect/token"
        self.jwks_endpoint = f"{server_url}/realms/{realm}/protocol/openid-connect/certs"
        
        # Token cache
        self._token_cache: Dict[str, Dict] = {}
    
    def authenticate(self, username: str, password: str) -> Dict:
        """
        Authenticate user with username and password (Direct Access Grant)
        
        Args:
            username: User's username
            password: User's password
            
        Returns:
            Token response containing access_token, refresh_token, etc.
        """
        data = {
            'grant_type': 'password',
            'client_id': self.client_id,
            'username': username,
            'password': password,
            'scope': 'openid profile email'
        }
        
        response = requests.post(self.token_endpoint, data=data)
        response.raise_for_status()
        
        token_data = response.json()
        
        # Cache token
        self._token_cache[username] = {
            'token_data': token_data,
            'timestamp': time.time()
        }
        
        return token_data
    
    def get_access_token(self, username: str, password: str, 
                        force_refresh: bool = False) -> str:
        """
        Get access token for user, using cache if available
        
        Args:
            username: User's username
            password: User's password
            force_refresh: Force token refresh even if cached
            
        Returns:
            Access token string
        """
        # Check cache
        if not force_refresh and username in self._token_cache:
            cache_entry = self._token_cache[username]
            token_data = cache_entry['token_data']
            timestamp = cache_entry['timestamp']
            expires_in = token_data.get('expires_in', 0)
            
            # Check if token is still valid (with 30 second buffer)
            if time.time() - timestamp < (expires_in - 30):
                return token_data['access_token']
        
        # Authenticate and get new token
        token_data = self.authenticate(username, password)
        return token_data['access_token']
    
    def refresh_token(self, refresh_token: str) -> Dict:
        """
        Refresh access token using refresh token
        
        Args:
            refresh_token: Refresh token
            
        Returns:
            New token response
        """
        data = {
            'grant_type': 'refresh_token',
            'client_id': self.client_id,
            'refresh_token': refresh_token
        }
        
        response = requests.post(self.token_endpoint, data=data)
        response.raise_for_status()
        
        return response.json()
    
    def decode_token(self, token: str, verify: bool = False) -> Dict:
        """
        Decode JWT token
        
        Args:
            token: JWT token string
            verify: Whether to verify token signature
            
        Returns:
            Decoded token payload
        """
        if verify:
            # Get JWKS for verification
            jwks_response = requests.get(self.jwks_endpoint)
            jwks = jwks_response.json()
            
            # Decode with verification
            return jwt.decode(
                token,
                options={"verify_signature": True},
                algorithms=["RS256"],
                audience=self.client_id
            )
        else:
            # Decode without verification (for inspection only)
            return jwt.decode(token, options={"verify_signature": False})
    
    def get_token_info(self, token: str) -> Dict:
        """
        Get detailed information about a token
        
        Args:
            token: JWT token string
            
        Returns:
            Dictionary with token information
        """
        try:
            payload = self.decode_token(token, verify=False)
            
            return {
                'subject': payload.get('sub'),
                'username': payload.get('preferred_username'),
                'email': payload.get('email'),
                'name': payload.get('name'),
                'groups': payload.get('groups', []),
                'roles': payload.get('roles', []),
                'issuer': payload.get('iss'),
                'audience': payload.get('aud'),
                'issued_at': payload.get('iat'),
                'expires_at': payload.get('exp'),
                'is_expired': payload.get('exp', 0) < time.time()
            }
        except Exception as e:
            return {'error': str(e)}
    
    def is_token_valid(self, token: str) -> bool:
        """
        Check if token is still valid
        
        Args:
            token: JWT token string
            
        Returns:
            True if token is valid and not expired
        """
        try:
            payload = self.decode_token(token, verify=False)
            exp = payload.get('exp', 0)
            return exp > time.time()
        except Exception:
            return False
    
    def get_user_roles(self, token: str) -> Tuple[list, list]:
        """
        Extract user roles and groups from token
        
        Args:
            token: JWT token string
            
        Returns:
            Tuple of (roles, groups)
        """
        try:
            payload = self.decode_token(token, verify=False)
            roles = payload.get('roles', [])
            groups = payload.get('groups', [])
            return roles, groups
        except Exception:
            return [], []


if __name__ == '__main__':
    # Example usage
    client = KeycloakClient(
        server_url='http://localhost:8080',
        realm='ozone',
        client_id='ozone-s3'
    )
    
    # Test authentication
    print("Testing Keycloak authentication...")
    try:
        token_data = client.authenticate('admin', 'admin123')
        print(f"✓ Authentication successful!")
        print(f"  Access token (first 50 chars): {token_data['access_token'][:50]}...")
        
        # Get token info
        info = client.get_token_info(token_data['access_token'])
        print(f"\nToken info:")
        print(f"  Username: {info['username']}")
        print(f"  Email: {info['email']}")
        print(f"  Groups: {info['groups']}")
        print(f"  Roles: {info['roles']}")
        print(f"  Expires at: {info['expires_at']}")
        print(f"  Is expired: {info['is_expired']}")
        
    except Exception as e:
        print(f"✗ Authentication failed: {e}")

