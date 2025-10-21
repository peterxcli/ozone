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
 * Request object for creating a remote volume.
 */
public class CreateRemoteVolumeRequest {

  @JsonProperty("owner_id")
  private String ownerId;

  @JsonProperty("remote_volume_name")
  private String remoteVolumeName;

  @JsonProperty("metadata_store_source")
  private MetadataStoreSource metadataStoreSource;

  @JsonProperty("metadata_store_info")
  private MetadataStoreInfo metadataStoreInfo;

  @JsonProperty("additional_juicefs_command")
  private String additionalJuiceFsCommand;

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public String getRemoteVolumeName() {
    return remoteVolumeName;
  }

  public void setRemoteVolumeName(String remoteVolumeName) {
    this.remoteVolumeName = remoteVolumeName;
  }

  public MetadataStoreSource getMetadataStoreSource() {
    return metadataStoreSource;
  }

  public void setMetadataStoreSource(MetadataStoreSource metadataStoreSource) {
    this.metadataStoreSource = metadataStoreSource;
  }

  public MetadataStoreInfo getMetadataStoreInfo() {
    return metadataStoreInfo;
  }

  public void setMetadataStoreInfo(MetadataStoreInfo metadataStoreInfo) {
    this.metadataStoreInfo = metadataStoreInfo;
  }

  public String getAdditionalJuiceFsCommand() {
    return additionalJuiceFsCommand;
  }

  public void setAdditionalJuiceFsCommand(String additionalJuiceFsCommand) {
    this.additionalJuiceFsCommand = additionalJuiceFsCommand;
  }
}
