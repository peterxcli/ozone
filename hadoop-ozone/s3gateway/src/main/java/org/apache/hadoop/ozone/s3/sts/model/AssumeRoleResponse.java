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

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * Response for AssumeRole and similar operations.
 */
@JacksonXmlRootElement(localName = "AssumeRoleResponse",
    namespace = "https://sts.amazonaws.com/doc/2011-06-15/")
public class AssumeRoleResponse {

  @JacksonXmlProperty(localName = "AssumeRoleResult")
  private AssumeRoleResult assumeRoleResult;

  @JacksonXmlProperty(localName = "ResponseMetadata")
  private ResponseMetadata responseMetadata;

  public AssumeRoleResponse() {
    this.responseMetadata = new ResponseMetadata();
  }

  public AssumeRoleResult getAssumeRoleResult() {
    return assumeRoleResult;
  }

  public void setAssumeRoleResult(AssumeRoleResult assumeRoleResult) {
    this.assumeRoleResult = assumeRoleResult;
  }

  public ResponseMetadata getResponseMetadata() {
    return responseMetadata;
  }

  public void setResponseMetadata(ResponseMetadata responseMetadata) {
    this.responseMetadata = responseMetadata;
  }

  /**
   * AssumeRole result element.
   */
  public static class AssumeRoleResult {

    @JacksonXmlProperty(localName = "Credentials")
    private Credentials credentials;

    @JacksonXmlProperty(localName = "AssumedRoleUser")
    private AssumedRoleUser assumedRoleUser;

    @JacksonXmlProperty(localName = "PackedPolicySize")
    private Integer packedPolicySize;

    @JacksonXmlProperty(localName = "JWTToken")
    private String jwtToken;

    public Credentials getCredentials() {
      return credentials;
    }

    public void setCredentials(Credentials credentials) {
      this.credentials = credentials;
    }

    public AssumedRoleUser getAssumedRoleUser() {
      return assumedRoleUser;
    }

    public void setAssumedRoleUser(AssumedRoleUser assumedRoleUser) {
      this.assumedRoleUser = assumedRoleUser;
    }

    public Integer getPackedPolicySize() {
      return packedPolicySize;
    }

    public void setPackedPolicySize(Integer packedPolicySize) {
      this.packedPolicySize = packedPolicySize;
    }

    public String getJwtToken() {
      return jwtToken;
    }

    public void setJwtToken(String jwtToken) {
      this.jwtToken = jwtToken;
    }
  }

  /**
   * Assumed role user element.
   */
  public static class AssumedRoleUser {

    @JacksonXmlProperty(localName = "AssumedRoleId")
    private String assumedRoleId;

    @JacksonXmlProperty(localName = "Arn")
    private String arn;

    public AssumedRoleUser() {
    }

    public AssumedRoleUser(String assumedRoleId, String arn) {
      this.assumedRoleId = assumedRoleId;
      this.arn = arn;
    }

    public String getAssumedRoleId() {
      return assumedRoleId;
    }

    public void setAssumedRoleId(String assumedRoleId) {
      this.assumedRoleId = assumedRoleId;
    }

    public String getArn() {
      return arn;
    }

    public void setArn(String arn) {
      this.arn = arn;
    }
  }

  /**
   * Response metadata element.
   */
  public static class ResponseMetadata {

    @JacksonXmlProperty(localName = "RequestId")
    private String requestId;

    public ResponseMetadata() {
      this.requestId = java.util.UUID.randomUUID().toString();
    }

    public String getRequestId() {
      return requestId;
    }

    public void setRequestId(String requestId) {
      this.requestId = requestId;
    }
  }
}

