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

package org.apache.hadoop.ozone.s3.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import javax.xml.bind.JAXBException;
import org.junit.jupiter.api.Test;

/**
 * Test S3 Select request parsing.
 */
public class TestSelectObjectContentRequest {

  @Test
  public void testParseCSVSelectRequest() throws JAXBException {
    String xmlRequest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<SelectObjectContentRequest>" +
        "  <Expression>SELECT * FROM S3Object WHERE age > 25</Expression>" +
        "  <ExpressionType>SQL</ExpressionType>" +
        "  <InputSerialization>" +
        "    <CSV>" +
        "      <FileHeaderInfo>USE</FileHeaderInfo>" +
        "      <FieldDelimiter>,</FieldDelimiter>" +
        "      <RecordDelimiter>\\n</RecordDelimiter>" +
        "    </CSV>" +
        "  </InputSerialization>" +
        "  <OutputSerialization>" +
        "    <CSV>" +
        "      <FieldDelimiter>,</FieldDelimiter>" +
        "      <RecordDelimiter>\\n</RecordDelimiter>" +
        "    </CSV>" +
        "  </OutputSerialization>" +
        "</SelectObjectContentRequest>";

    InputStream inputStream = new ByteArrayInputStream(xmlRequest.getBytes());
    SelectObjectContentRequest request = 
        SelectObjectContentRequest.parseFrom(inputStream);

    assertNotNull(request);
    assertEquals("SELECT * FROM S3Object WHERE age > 25", request.getExpression());
    assertEquals("SQL", request.getExpressionType());
    assertNotNull(request.getInputSerialization());
    assertNotNull(request.getInputSerialization().getCsv());
    assertEquals("USE", request.getInputSerialization().getCsv().getFileHeaderInfo());
    assertEquals(",", request.getInputSerialization().getCsv().getFieldDelimiter());
    assertNotNull(request.getOutputSerialization());
    assertNotNull(request.getOutputSerialization().getCsv());
    assertEquals(",", request.getOutputSerialization().getCsv().getFieldDelimiter());
  }

  @Test
  public void testParseJSONSelectRequest() throws JAXBException {
    String xmlRequest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<SelectObjectContentRequest>" +
        "  <Expression>SELECT * FROM S3Object[*] s WHERE s.age > 25</Expression>" +
        "  <ExpressionType>SQL</ExpressionType>" +
        "  <InputSerialization>" +
        "    <JSON>" +
        "      <Type>LINES</Type>" +
        "    </JSON>" +
        "  </InputSerialization>" +
        "  <OutputSerialization>" +
        "    <JSON>" +
        "      <RecordDelimiter>\\n</RecordDelimiter>" +
        "    </JSON>" +
        "  </OutputSerialization>" +
        "</SelectObjectContentRequest>";

    InputStream inputStream = new ByteArrayInputStream(xmlRequest.getBytes());
    SelectObjectContentRequest request = 
        SelectObjectContentRequest.parseFrom(inputStream);

    assertNotNull(request);
    assertEquals("SELECT * FROM S3Object[*] s WHERE s.age > 25", 
        request.getExpression());
    assertEquals("SQL", request.getExpressionType());
    assertNotNull(request.getInputSerialization());
    assertNotNull(request.getInputSerialization().getJson());
    assertEquals("LINES", request.getInputSerialization().getJson().getType());
    assertNotNull(request.getOutputSerialization());
    assertNotNull(request.getOutputSerialization().getJson());
  }

  @Test
  public void testParseParquetSelectRequest() throws JAXBException {
    String xmlRequest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<SelectObjectContentRequest>" +
        "  <Expression>SELECT * FROM S3Object LIMIT 10</Expression>" +
        "  <ExpressionType>SQL</ExpressionType>" +
        "  <InputSerialization>" +
        "    <Parquet/>" +
        "  </InputSerialization>" +
        "  <OutputSerialization>" +
        "    <CSV>" +
        "      <FieldDelimiter>,</FieldDelimiter>" +
        "    </CSV>" +
        "  </OutputSerialization>" +
        "</SelectObjectContentRequest>";

    InputStream inputStream = new ByteArrayInputStream(xmlRequest.getBytes());
    SelectObjectContentRequest request = 
        SelectObjectContentRequest.parseFrom(inputStream);

    assertNotNull(request);
    assertEquals("SELECT * FROM S3Object LIMIT 10", request.getExpression());
    assertNotNull(request.getInputSerialization());
    assertNotNull(request.getInputSerialization().getParquet());
    assertNull(request.getInputSerialization().getCsv());
    assertNull(request.getInputSerialization().getJson());
  }

  @Test
  public void testParseSelectRequestWithScanRange() throws JAXBException {
    String xmlRequest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<SelectObjectContentRequest>" +
        "  <Expression>SELECT * FROM S3Object</Expression>" +
        "  <ExpressionType>SQL</ExpressionType>" +
        "  <InputSerialization>" +
        "    <CSV/>" +
        "  </InputSerialization>" +
        "  <OutputSerialization>" +
        "    <CSV/>" +
        "  </OutputSerialization>" +
        "  <ScanRange>" +
        "    <Start>50</Start>" +
        "    <End>1000</End>" +
        "  </ScanRange>" +
        "</SelectObjectContentRequest>";

    InputStream inputStream = new ByteArrayInputStream(xmlRequest.getBytes());
    SelectObjectContentRequest request = 
        SelectObjectContentRequest.parseFrom(inputStream);

    assertNotNull(request);
    assertNotNull(request.getScanRange());
    assertEquals(Long.valueOf(50), request.getScanRange().getStart());
    assertEquals(Long.valueOf(1000), request.getScanRange().getEnd());
  }

  @Test
  public void testParseSelectRequestWithCompressionType() throws JAXBException {
    String xmlRequest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<SelectObjectContentRequest>" +
        "  <Expression>SELECT * FROM S3Object</Expression>" +
        "  <ExpressionType>SQL</ExpressionType>" +
        "  <InputSerialization>" +
        "    <CompressionType>GZIP</CompressionType>" +
        "    <CSV/>" +
        "  </InputSerialization>" +
        "  <OutputSerialization>" +
        "    <CSV/>" +
        "  </OutputSerialization>" +
        "</SelectObjectContentRequest>";

    InputStream inputStream = new ByteArrayInputStream(xmlRequest.getBytes());
    SelectObjectContentRequest request = 
        SelectObjectContentRequest.parseFrom(inputStream);

    assertNotNull(request);
    assertNotNull(request.getInputSerialization());
    assertEquals("GZIP", request.getInputSerialization().getCompressionType());
  }
}

