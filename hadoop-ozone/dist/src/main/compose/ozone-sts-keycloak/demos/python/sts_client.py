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
STS (Security Token Service) client for Ozone
"""

import xml.etree.ElementTree as ET
from datetime import datetime
from typing import Dict, Optional
import requests


class STSCredentials:
    """Container for temporary S3 credentials"""
    
    def __init__(self, access_key_id: str, secret_access_key: str,
                 session_token: str, expiration: str):
        """
        Initialize STS credentials
        
        Args:
            access_key_id: Temporary access key ID
            secret_access_key: Temporary secret access key
            session_token: Session token
            expiration: Expiration timestamp
        """
        self.access_key_id = access_key_id
        self.secret_access_key = secret_access_key
        self.session_token = session_token
        self.expiration = expiration
    
    def to_dict(self) -> Dict:
        """Convert credentials to dictionary"""
        return {
            'AccessKeyId': self.access_key_id,
            'SecretAccessKey': self.secret_access_key,
            'SessionToken': self.session_token,
            'Expiration': self.expiration
        }
    
    def to_aws_config(self) -> Dict:
        """Convert to AWS SDK configuration format"""
        return {
            'aws_access_key_id': self.access_key_id,
            'aws_secret_access_key': self.secret_access_key,
            'aws_session_token': self.session_token
        }
    
    def is_expired(self) -> bool:
        """Check if credentials are expired"""
        try:
            exp_time = datetime.fromisoformat(self.expiration.replace('Z', '+00:00'))
            return exp_time < datetime.now(exp_time.tzinfo)
        except Exception:
            return True
    
    def __str__(self) -> str:
        return (f"STSCredentials(AccessKeyId={self.access_key_id}, "
                f"Expiration={self.expiration})")


class STSClient:
    """Client for Ozone STS service"""
    
    def __init__(self, endpoint_url: str):
        """
        Initialize STS client
        
        Args:
            endpoint_url: STS endpoint URL (e.g., http://localhost:9878)
        """
        self.endpoint_url = endpoint_url.rstrip('/')
    
    def assume_role_with_web_identity(
        self,
        web_identity_token: str,
        role_arn: str,
        role_session_name: str,
        duration_seconds: int = 3600
    ) -> STSCredentials:
        """
        Assume role using web identity token (OIDC)
        
        Args:
            web_identity_token: OIDC/JWT token from identity provider
            role_arn: ARN of the role to assume
            role_session_name: Session name for the role
            duration_seconds: Credential lifetime in seconds
            
        Returns:
            STSCredentials object
            
        Raises:
            Exception if the request fails
        """
        params = {
            'Action': 'AssumeRoleWithWebIdentity',
            'Version': '2011-06-15',
            'WebIdentityToken': web_identity_token,
            'RoleArn': role_arn,
            'RoleSessionName': role_session_name,
            'DurationSeconds': str(duration_seconds)
        }
        
        response = requests.post(
            self.endpoint_url,
            data=params,
            headers={'Content-Type': 'application/x-www-form-urlencoded'}
        )
        
        if response.status_code != 200:
            raise Exception(f"STS request failed: {response.status_code} - {response.text}")
        
        # Parse XML response
        return self._parse_assume_role_response(response.text)
    
    def _parse_assume_role_response(self, xml_response: str) -> STSCredentials:
        """
        Parse AssumeRoleWithWebIdentity XML response
        
        Args:
            xml_response: XML response string
            
        Returns:
            STSCredentials object
        """
        try:
            root = ET.fromstring(xml_response)
            
            # Check for errors
            error = root.find('.//{https://sts.amazonaws.com/doc/2011-06-15/}Error')
            if error is not None:
                code = error.find('.//{https://sts.amazonaws.com/doc/2011-06-15/}Code')
                message = error.find('.//{https://sts.amazonaws.com/doc/2011-06-15/}Message')
                error_msg = f"STS Error: {code.text if code is not None else 'Unknown'}"
                if message is not None:
                    error_msg += f" - {message.text}"
                raise Exception(error_msg)
            
            # Extract credentials
            ns = {'sts': 'https://sts.amazonaws.com/doc/2011-06-15/'}
            
            creds = root.find('.//sts:Credentials', ns)
            if creds is None:
                # Try without namespace
                creds = root.find('.//Credentials')
            
            if creds is None:
                raise Exception(f"Could not find Credentials in response: {xml_response[:200]}")
            
            access_key_id = self._get_text(creds, 'AccessKeyId', ns)
            secret_access_key = self._get_text(creds, 'SecretAccessKey', ns)
            session_token = self._get_text(creds, 'SessionToken', ns)
            expiration = self._get_text(creds, 'Expiration', ns)
            
            return STSCredentials(
                access_key_id=access_key_id,
                secret_access_key=secret_access_key,
                session_token=session_token,
                expiration=expiration
            )
            
        except ET.ParseError as e:
            raise Exception(f"Failed to parse STS response: {e}")
    
    def _get_text(self, element: ET.Element, tag: str, 
                  namespace: Optional[Dict] = None) -> str:
        """
        Get text from XML element, trying with and without namespace
        
        Args:
            element: Parent element
            tag: Tag name to find
            namespace: Namespace dict
            
        Returns:
            Element text or empty string
        """
        if namespace:
            child = element.find(f"sts:{tag}", namespace)
        else:
            child = None
        
        if child is None:
            child = element.find(tag)
        
        return child.text if child is not None and child.text else ''
    
    def get_caller_identity(self, credentials: STSCredentials) -> Dict:
        """
        Get caller identity information
        
        Args:
            credentials: STS credentials to use
            
        Returns:
            Dictionary with caller identity information
        """
        params = {
            'Action': 'GetCallerIdentity',
            'Version': '2011-06-15'
        }
        
        # Add credentials as headers or query params
        headers = {
            'Authorization': f"AWS4-HMAC-SHA256 Credential={credentials.access_key_id}",
            'X-Amz-Security-Token': credentials.session_token
        }
        
        response = requests.post(
            self.endpoint_url,
            data=params,
            headers=headers
        )
        
        if response.status_code != 200:
            raise Exception(f"GetCallerIdentity failed: {response.text}")
        
        # Parse response (simplified)
        return {'response': response.text}


if __name__ == '__main__':
    # Example usage
    import sys
    
    if len(sys.argv) < 2:
        print("Usage: python sts_client.py <web_identity_token>")
        sys.exit(1)
    
    token = sys.argv[1]
    
    print("Testing STS AssumeRoleWithWebIdentity...")
    
    client = STSClient('http://localhost:9878')
    
    try:
        creds = client.assume_role_with_web_identity(
            web_identity_token=token,
            role_arn='arn:ozone:iam::test-role',
            role_session_name='test-session'
        )
        
        print(f"✓ AssumeRoleWithWebIdentity successful!")
        print(f"\nCredentials:")
        print(f"  Access Key ID: {creds.access_key_id}")
        print(f"  Secret Access Key: {creds.secret_access_key[:20]}...")
        print(f"  Session Token: {creds.session_token[:50]}...")
        print(f"  Expiration: {creds.expiration}")
        print(f"  Is Expired: {creds.is_expired()}")
        
    except Exception as e:
        print(f"✗ STS request failed: {e}")
        sys.exit(1)

