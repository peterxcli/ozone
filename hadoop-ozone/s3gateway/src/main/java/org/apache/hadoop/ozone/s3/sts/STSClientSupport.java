/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.s3.sts;

import java.util.Optional;
import org.apache.hadoop.ozone.om.helpers.S3SecretValue;
import org.apache.hadoop.ozone.s3.sts.token.TemporaryCredentials;
import org.apache.hadoop.ozone.s3.sts.token.TokenValidator;

/**
 * Helper class to support STS temporary credentials in OzoneClient.
 */
public final class STSClientSupport {

  private STSClientSupport() {
  }

  /**
   * Check if an access key is a temporary STS credential.
   *
   * @param accessKeyId access key ID
   * @return true if STS credential
   */
  public static boolean isSTSCredential(String accessKeyId) {
    return accessKeyId != null && accessKeyId.startsWith("STS-");
  }

  /**
   * Convert temporary credentials to S3SecretValue for Ozone client.
   *
   * @param credentials temporary credentials
   * @return S3SecretValue
   */
  public static S3SecretValue toS3SecretValue(TemporaryCredentials credentials) {
    if (credentials == null) {
      return null;
    }

    return S3SecretValue.of(
        credentials.getPrincipal(),
        credentials.getSecretAccessKey()
    );
  }

  /**
   * Extract principal from STS credentials using validator.
   *
   * @param validator token validator
   * @param accessKeyId access key ID
   * @param sessionToken session token
   * @return principal if valid, null otherwise
   */
  public static String extractPrincipal(TokenValidator validator,
      String accessKeyId, String sessionToken) {
    if (validator == null || !isSTSCredential(accessKeyId)) {
      return null;
    }

    TokenValidator.ValidationResult result =
        validator.validate(accessKeyId, sessionToken);
    
    if (result.isValid()) {
      Optional<TemporaryCredentials> creds = result.getCredentials();
      return creds.map(TemporaryCredentials::getPrincipal).orElse(null);
    }

    return null;
  }

  /**
   * Get secret key from STS credentials using validator.
   *
   * @param validator token validator
   * @param accessKeyId access key ID
   * @param sessionToken session token
   * @return secret key if valid, null otherwise
   */
  public static String extractSecretKey(TokenValidator validator,
      String accessKeyId, String sessionToken) {
    if (validator == null || !isSTSCredential(accessKeyId)) {
      return null;
    }

    TokenValidator.ValidationResult result =
        validator.validate(accessKeyId, sessionToken);
    
    if (result.isValid()) {
      Optional<TemporaryCredentials> creds = result.getCredentials();
      return creds.map(TemporaryCredentials::getSecretAccessKey).orElse(null);
    }

    return null;
  }
}

