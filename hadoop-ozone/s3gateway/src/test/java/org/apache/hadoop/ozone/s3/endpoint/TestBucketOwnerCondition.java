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

package org.apache.hadoop.ozone.s3.endpoint;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientStub;
import org.apache.hadoop.ozone.s3.exception.OS3Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * This class tests the bucket owner condition check functionality.
 */
public class TestBucketOwnerCondition {

  private static final String BUCKET_NAME = OzoneConsts.BUCKET;
  private static final String BUCKET_OWNER = "testOwner";
  private static final String EXPECTED_BUCKET_OWNER_HEADER = "x-amz-expected-bucket-owner";
  
  private OzoneClient clientStub;
  private BucketEndpoint bucketEndpoint;
  private ContainerRequestContext mockContext;
//   private EndpointBase endpointBase;

  @BeforeEach
  public void setup() throws Exception {
    clientStub = new OzoneClientStub();
    clientStub.getObjectStore().createS3Bucket(BUCKET_NAME);
    
    // Create mock context for headers
    mockContext = mock(ContainerRequestContext.class);
    when(mockContext.getUriInfo()).thenReturn(mock(UriInfo.class));
    when(mockContext.getUriInfo().getPathParameters())
        .thenReturn(new MultivaluedHashMap<>());
    when(mockContext.getUriInfo().getQueryParameters())
        .thenReturn(new MultivaluedHashMap<>());

    // Create BucketEndpoint and setClient to OzoneClientStub
    bucketEndpoint = EndpointBuilder.newBucketEndpointBuilder()
        .setClient(clientStub)
        .setContext(mockContext)
        .build();
    
    // // Create a mock EndpointBase to test the bucket owner condition check directly
    // endpointBase = mock(EndpointBase.class);
    // when(endpointBase.getBucket(BUCKET_NAME)).thenCallRealMethod();
  }

  @Test
  public void testHeadBucketWithCorrectOwner() throws Exception {
    // Set the expected bucket owner header to match the actual owner
    when(mockContext.getHeaderString(EXPECTED_BUCKET_OWNER_HEADER))
        .thenReturn(BUCKET_OWNER);

    // Mock the bucket owner
    OzoneBucket mockBucket = mock(OzoneBucket.class);
    when(mockBucket.getOwner()).thenReturn(BUCKET_OWNER);
    
    // No exception should be thrown
    Response response = bucketEndpoint.head(BUCKET_NAME);
    assertEquals(200, response.getStatus());
  }

  @Test
  public void testHeadBucketWithIncorrectOwner() throws Exception {
    // Set the expected bucket owner header to a different value
    when(mockContext.getHeaderString(EXPECTED_BUCKET_OWNER_HEADER))
        .thenReturn("wrongOwner");

    OS3Exception ex = assertThrows(OS3Exception.class, () ->
        bucketEndpoint.head(BUCKET_NAME));
    assertEquals(HTTP_FORBIDDEN, ex.getHttpCode());
    assertEquals("BucketOwnerMismatch", ex.getCode());
  }

  @Test
  public void testHeadBucketWithNoOwnerHeader() throws Exception {
    // Don't set the expected bucket owner header
    when(mockContext.getHeaderString(EXPECTED_BUCKET_OWNER_HEADER))
        .thenReturn(null);

    Response response = bucketEndpoint.head(BUCKET_NAME);
    assertEquals(200, response.getStatus());
  }
}
