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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.ws.rs.core.Response;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClientStub;
import org.apache.hadoop.ozone.s3.select.SelectObjectContentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test S3 Select functionality.
 */
public class TestSelectObjectEndpoint {

  private OzoneClientStub clientStub;
  private ObjectStore objectStore;
  private ObjectEndpoint objectEndpoint;
  private OzoneBucket bucket;

  @BeforeEach
  public void setup() throws Exception {
    OzoneConfiguration config = new OzoneConfiguration();
    clientStub = new OzoneClientStub();
    objectStore = clientStub.getObjectStore();
    
    objectEndpoint = new ObjectEndpoint();
    objectEndpoint.setClient(clientStub);
    objectEndpoint.setOzoneConfiguration(config);
    objectEndpoint.init();

    String bucketName = "bucket1";
    objectStore.createS3Bucket(bucketName);
    bucket = objectStore.getS3Bucket(bucketName);
  }

  @Test
  public void testSelectObjectWithNullParameters() throws Exception {
    // Test that when select parameters are null, the endpoint returns null
    Response response = objectEndpoint.selectObjectContent(
        "bucket1", "key1", null, null, null);
    assertNull(response);
  }

  @Test
  public void testSelectObjectWithInvalidSelectType() throws Exception {
    // Test that when select-type is not "2", the endpoint returns null
    Response response = objectEndpoint.selectObjectContent(
        "bucket1", "key1", "present", "1", null);
    assertNull(response);
  }

  @Test
  public void testSelectCSVRequest() throws Exception {
    // Create test CSV data
    String csvData = "name,age,city\n" +
                    "John,30,NYC\n" +
                    "Jane,25,LA\n" +
                    "Bob,35,Chicago\n";
    
    bucket.createKey("test.csv", csvData.length()).write(csvData.getBytes());

    // Create S3 Select request
    SelectObjectContentRequest request = createCSVSelectRequest(
        "SELECT * FROM S3Object WHERE age > 25");

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    JAXBContext context = JAXBContext.newInstance(SelectObjectContentRequest.class);
    Marshaller marshaller = context.createMarshaller();
    marshaller.marshal(request, baos);
    
    InputStream requestBody = new ByteArrayInputStream(baos.toByteArray());

    Response response = objectEndpoint.selectObjectContent(
        "bucket1", "test.csv", "present", "2", requestBody);
    
    assertNotNull(response);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  public void testSelectParquetRequest() throws Exception {
    // Create test key (Parquet testing would need actual Parquet data)
    bucket.createKey("test.parquet", 100).write(new byte[100]);

    SelectObjectContentRequest request = createParquetSelectRequest(
        "SELECT * FROM S3Object LIMIT 10");

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    JAXBContext context = JAXBContext.newInstance(SelectObjectContentRequest.class);
    Marshaller marshaller = context.createMarshaller();
    marshaller.marshal(request, baos);
    
    InputStream requestBody = new ByteArrayInputStream(baos.toByteArray());

    Response response = objectEndpoint.selectObjectContent(
        "bucket1", "test.parquet", "present", "2", requestBody);
    
    assertNotNull(response);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  public void testSelectWithScanRange() throws Exception {
    String csvData = "name,age,city\n" +
                    "John,30,NYC\n" +
                    "Jane,25,LA\n" +
                    "Bob,35,Chicago\n";
    
    bucket.createKey("rangetest.csv", csvData.length()).write(csvData.getBytes());

    SelectObjectContentRequest request = createCSVSelectRequest(
        "SELECT * FROM S3Object");
    
    // Add scan range
    SelectObjectContentRequest.ScanRange scanRange = 
        new SelectObjectContentRequest.ScanRange();
    scanRange.setStart(0L);
    scanRange.setEnd(50L);
    request.setScanRange(scanRange);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    JAXBContext context = JAXBContext.newInstance(SelectObjectContentRequest.class);
    Marshaller marshaller = context.createMarshaller();
    marshaller.marshal(request, baos);
    
    InputStream requestBody = new ByteArrayInputStream(baos.toByteArray());

    Response response = objectEndpoint.selectObjectContent(
        "bucket1", "rangetest.csv", "present", "2", requestBody);
    
    assertNotNull(response);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  private SelectObjectContentRequest createCSVSelectRequest(String expression) {
    SelectObjectContentRequest request = new SelectObjectContentRequest();
    request.setExpression(expression);
    request.setExpressionType("SQL");
    
    SelectObjectContentRequest.InputSerialization input = 
        new SelectObjectContentRequest.InputSerialization();
    SelectObjectContentRequest.CSVInput csvInput = 
        new SelectObjectContentRequest.CSVInput();
    csvInput.setFileHeaderInfo("USE");
    csvInput.setFieldDelimiter(",");
    csvInput.setRecordDelimiter("\n");
    input.setCsv(csvInput);
    request.setInputSerialization(input);
    
    SelectObjectContentRequest.OutputSerialization output = 
        new SelectObjectContentRequest.OutputSerialization();
    SelectObjectContentRequest.CSVOutput csvOutput = 
        new SelectObjectContentRequest.CSVOutput();
    csvOutput.setFieldDelimiter(",");
    csvOutput.setRecordDelimiter("\n");
    output.setCsv(csvOutput);
    request.setOutputSerialization(output);
    
    return request;
  }

  private SelectObjectContentRequest createParquetSelectRequest(String expression) {
    SelectObjectContentRequest request = new SelectObjectContentRequest();
    request.setExpression(expression);
    request.setExpressionType("SQL");
    
    SelectObjectContentRequest.InputSerialization input = 
        new SelectObjectContentRequest.InputSerialization();
    SelectObjectContentRequest.ParquetInput parquetInput = 
        new SelectObjectContentRequest.ParquetInput();
    input.setParquet(parquetInput);
    request.setInputSerialization(input);
    
    SelectObjectContentRequest.OutputSerialization output = 
        new SelectObjectContentRequest.OutputSerialization();
    SelectObjectContentRequest.CSVOutput csvOutput = 
        new SelectObjectContentRequest.CSVOutput();
    csvOutput.setFieldDelimiter(",");
    csvOutput.setRecordDelimiter("\n");
    output.setCsv(csvOutput);
    request.setOutputSerialization(output);
    
    return request;
  }
}
