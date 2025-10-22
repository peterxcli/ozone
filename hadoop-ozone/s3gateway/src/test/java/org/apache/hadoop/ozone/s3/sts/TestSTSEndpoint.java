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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.OzoneConfigurationHolder;
import org.apache.hadoop.ozone.s3.sts.model.STSRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for STSEndpoint.
 */
public class TestSTSEndpoint {

  private STSEndpoint endpoint;
  private OzoneConfiguration config;

  @BeforeEach
  public void setup() {
    config = new OzoneConfiguration();
    config.setBoolean(STSConfigKeys.OZONE_STS_ENABLED, true);
    config.set(STSConfigKeys.OZONE_STS_AUTH_PROVIDERS, "IDP,REST_API");
    config.set(STSConfigKeys.OZONE_STS_IDP_OIDC_ISSUER,
        "https://accounts.google.com");
    config.set(STSConfigKeys.OZONE_STS_REST_AUTH_URL,
        "http://localhost:9999/auth");

    OzoneConfigurationHolder.setConfiguration(config);

    endpoint = new STSEndpoint();
    endpoint.init();
  }

  @AfterEach
  public void cleanup() {
    if (endpoint != null) {
      endpoint.cleanup();
    }
    OzoneConfigurationHolder.resetConfiguration();
  }

  @Test
  public void testHealthEndpoint() {
    Response response = endpoint.health();
    
    assertNotNull(response);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  public void testMissingAction() {
    STSRequest request = new STSRequest();
    // No action set

    Response response = endpoint.handlePost(request);

    assertNotNull(response);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
        response.getStatus());
    assertEquals(MediaType.APPLICATION_XML, response.getMediaType().toString());
  }

  @Test
  public void testUnknownAction() {
    STSRequest request = new STSRequest();
    request.setAction("UnknownAction");

    Response response = endpoint.handlePost(request);

    assertNotNull(response);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
        response.getStatus());
  }

  @Test
  public void testGetCallerIdentity() {
    STSRequest request = new STSRequest();
    request.setAction("GetCallerIdentity");

    Response response = endpoint.handlePost(request);

    assertNotNull(response);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(MediaType.APPLICATION_XML, response.getMediaType().toString());
  }

  @Test
  public void testSTSDisabled() {
    endpoint.cleanup();
    
    OzoneConfiguration disabledConfig = new OzoneConfiguration();
    disabledConfig.setBoolean(STSConfigKeys.OZONE_STS_ENABLED, false);
    OzoneConfigurationHolder.resetConfiguration();
    OzoneConfigurationHolder.setConfiguration(disabledConfig);

    STSEndpoint disabledEndpoint = new STSEndpoint();
    disabledEndpoint.init();

    STSRequest request = new STSRequest();
    request.setAction("GetCallerIdentity");

    Response response = disabledEndpoint.handlePost(request);

    assertNotNull(response);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
        response.getStatus());
    
    disabledEndpoint.cleanup();
  }

  @Test
  public void testGetRequest() {
    STSRequest request = new STSRequest();
    request.setAction("GetCallerIdentity");

    // Test GET method
    Response response = endpoint.handleGet(request);

    assertNotNull(response);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }
}

