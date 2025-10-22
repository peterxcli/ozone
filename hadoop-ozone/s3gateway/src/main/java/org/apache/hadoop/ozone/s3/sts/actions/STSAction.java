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

package org.apache.hadoop.ozone.s3.sts.actions;

import javax.ws.rs.core.Response;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.sts.auth.AuthenticationProviderFactory;
import org.apache.hadoop.ozone.s3.sts.token.JWTTokenGenerator;
import org.apache.hadoop.ozone.s3.sts.token.STSCredentialGenerator;
import org.apache.hadoop.ozone.s3.sts.token.TokenStore;

/**
 * Base interface for STS actions.
 */
public interface STSAction {

  /**
   * Execute the STS action.
   *
   * @return HTTP response
   */
  Response execute();

  /**
   * Get the action name.
   *
   * @return action name
   */
  String getActionName();

  /**
   * Initialize the action with dependencies.
   *
   * @param config configuration
   * @param authProviderFactory authentication provider factory
   * @param credentialGenerator credential generator
   * @param tokenStore token store
   * @param jwtGenerator JWT generator
   */
  void initialize(OzoneConfiguration config,
      AuthenticationProviderFactory authProviderFactory,
      STSCredentialGenerator credentialGenerator,
      TokenStore tokenStore,
      JWTTokenGenerator jwtGenerator);
}

