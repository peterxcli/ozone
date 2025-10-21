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

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST endpoint for remote volume operations.
 */
@Path("/remote-volumes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RemoteVolumeEndpoint extends EndpointBase {

  private static final Logger LOG = LoggerFactory.getLogger(RemoteVolumeEndpoint.class);

  @Inject
  private OzoneConfiguration configuration;

  private RemoteVolumeService remoteVolumeService;

  private RemoteVolumeService getRemoteVolumeService() {
    if (remoteVolumeService == null) {
      remoteVolumeService = new RemoteVolumeService(configuration, getObjectStore());
    }
    return remoteVolumeService;
  }

  /**
   * Create a new remote volume.
   */
  @POST
  public Response createRemoteVolume(CreateRemoteVolumeRequest request) {
    try {
      LOG.info("Creating remote volume: {} for owner: {}",
          request.getRemoteVolumeName(), request.getOwnerId());

      // Validate request
      validateRequest(request);

      // Create remote volume
      CreateRemoteVolumeResponse response = getRemoteVolumeService()
          .createRemoteVolume(request);

      LOG.info("Successfully created remote volume: {}", request.getRemoteVolumeName());
      return Response.status(201).entity(response).build();

    } catch (IllegalArgumentException e) {
      LOG.error("Invalid request: {}", e.getMessage());
      return Response.status(400)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (RemoteVolumeAlreadyExistsException e) {
      LOG.error("Remote volume already exists: {}", e.getMessage());
      return Response.status(409)
          .entity(new ErrorResponse(e.getMessage()))
          .build();
    } catch (Exception e) {
      LOG.error("Error creating remote volume: {}", e.getMessage(), e);
      return Response.status(500)
          .entity(new ErrorResponse("Internal server error: " + e.getMessage()))
          .build();
    }
  }

  private void validateRequest(CreateRemoteVolumeRequest request) {
    if (request.getOwnerId() == null || request.getOwnerId().trim().isEmpty()) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (request.getRemoteVolumeName() == null || request.getRemoteVolumeName().trim().isEmpty()) {
      throw new IllegalArgumentException("Remote volume name is required");
    }
    if (request.getMetadataStoreSource() == null) {
      throw new IllegalArgumentException("Metadata store source is required");
    }

    // Validate metadata store source
    if (request.getMetadataStoreSource() == MetadataStoreSource.MANAGED) {
      throw new IllegalArgumentException("Managed metadata store source is not yet supported");
    }

    // Validate metadata store info for external source
    if (request.getMetadataStoreSource() == MetadataStoreSource.EXTERNAL) {
      if (request.getMetadataStoreInfo() == null ||
          request.getMetadataStoreInfo().getConnectionString() == null ||
          request.getMetadataStoreInfo().getConnectionString().trim().isEmpty()) {
        throw new IllegalArgumentException(
            "Metadata store connection string is required for external source");
      }
    }
  }

  /**
   * Error response.
   */
  public static class ErrorResponse {
    private String error;

    public ErrorResponse(String error) {
      this.error = error;
    }

    public String getError() {
      return error;
    }

    public void setError(String error) {
      this.error = error;
    }
  }
}
