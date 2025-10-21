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

package org.apache.ozone.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response object for remote volume creation.
 */
public class CreateRemoteVolumeResponse {

  @JsonProperty("remote_volume_id")
  private String remoteVolumeId;

  @JsonProperty("remote_volume_name")
  private String remoteVolumeName;

  @JsonProperty("owner_id")
  private String ownerId;

  @JsonProperty("bucket_name")
  private String bucketName;

  @JsonProperty("access_key")
  private String accessKey;

  @JsonProperty("secret_key")
  private String secretKey;

  @JsonProperty("metadata_connection_string")
  private String metadataConnectionString;

  @JsonProperty("status")
  private String status;

  @JsonProperty("message")
  private String message;

  public String getRemoteVolumeId() {
    return remoteVolumeId;
  }

  public void setRemoteVolumeId(String remoteVolumeId) {
    this.remoteVolumeId = remoteVolumeId;
  }

  public String getRemoteVolumeName() {
    return remoteVolumeName;
  }

  public void setRemoteVolumeName(String remoteVolumeName) {
    this.remoteVolumeName = remoteVolumeName;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public String getBucketName() {
    return bucketName;
  }

  public void setBucketName(String bucketName) {
    this.bucketName = bucketName;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public void setAccessKey(String accessKey) {
    this.accessKey = accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
  }

  public String getMetadataConnectionString() {
    return metadataConnectionString;
  }

  public void setMetadataConnectionString(String metadataConnectionString) {
    this.metadataConnectionString = metadataConnectionString;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
