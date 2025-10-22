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

package org.apache.hadoop.ozone.s3.sts.model;

import javax.ws.rs.FormParam;
import javax.ws.rs.QueryParam;

/**
 * Request for AssumeRoleWithWebIdentity operation.
 */
public class AssumeRoleWithWebIdentityRequest extends STSRequest {

  @FormParam("RoleArn")
  @QueryParam("RoleArn")
  private String roleArn;

  @FormParam("RoleSessionName")
  @QueryParam("RoleSessionName")
  private String roleSessionName;

  @FormParam("WebIdentityToken")
  @QueryParam("WebIdentityToken")
  private String webIdentityToken;

  @FormParam("ProviderId")
  @QueryParam("ProviderId")
  private String providerId;

  @FormParam("Policy")
  @QueryParam("Policy")
  private String policy;

  public String getRoleArn() {
    return roleArn;
  }

  public void setRoleArn(String roleArn) {
    this.roleArn = roleArn;
  }

  public String getRoleSessionName() {
    return roleSessionName;
  }

  public void setRoleSessionName(String roleSessionName) {
    this.roleSessionName = roleSessionName;
  }

  public String getWebIdentityToken() {
    return webIdentityToken;
  }

  public void setWebIdentityToken(String webIdentityToken) {
    this.webIdentityToken = webIdentityToken;
  }

  public String getProviderId() {
    return providerId;
  }

  public void setProviderId(String providerId) {
    this.providerId = providerId;
  }

  public String getPolicy() {
    return policy;
  }

  public void setPolicy(String policy) {
    this.policy = policy;
  }
}

