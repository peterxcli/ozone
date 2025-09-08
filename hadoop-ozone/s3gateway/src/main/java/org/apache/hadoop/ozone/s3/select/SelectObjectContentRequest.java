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

import java.io.InputStream;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * S3 Select request object for parsing SelectObjectContent requests.
 */
@XmlRootElement(name = "SelectObjectContentRequest")
@XmlAccessorType(XmlAccessType.FIELD)
public class SelectObjectContentRequest {

  @XmlElement(name = "Expression")
  private String expression;

  @XmlElement(name = "ExpressionType")
  private String expressionType;

  @XmlElement(name = "InputSerialization")
  private InputSerialization inputSerialization;

  @XmlElement(name = "OutputSerialization")
  private OutputSerialization outputSerialization;

  @XmlElement(name = "ScanRange")
  private ScanRange scanRange;

  public String getExpression() {
    return expression;
  }

  public void setExpression(String expression) {
    this.expression = expression;
  }

  public String getExpressionType() {
    return expressionType;
  }

  public void setExpressionType(String expressionType) {
    this.expressionType = expressionType;
  }

  public InputSerialization getInputSerialization() {
    return inputSerialization;
  }

  public void setInputSerialization(InputSerialization inputSerialization) {
    this.inputSerialization = inputSerialization;
  }

  public OutputSerialization getOutputSerialization() {
    return outputSerialization;
  }

  public void setOutputSerialization(OutputSerialization outputSerialization) {
    this.outputSerialization = outputSerialization;
  }

  public ScanRange getScanRange() {
    return scanRange;
  }

  public void setScanRange(ScanRange scanRange) {
    this.scanRange = scanRange;
  }

  public static SelectObjectContentRequest parseFrom(InputStream inputStream)
      throws JAXBException {
    JAXBContext context = JAXBContext.newInstance(SelectObjectContentRequest.class);
    Unmarshaller unmarshaller = context.createUnmarshaller();
    return (SelectObjectContentRequest) unmarshaller.unmarshal(inputStream);
  }

  /**
   * Input serialization configuration.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class InputSerialization {
    @XmlElement(name = "CSV")
    private CSVInput csv;

    @XmlElement(name = "JSON")
    private JSONInput json;

    @XmlElement(name = "Parquet")
    private ParquetInput parquet;

    @XmlElement(name = "CompressionType")
    private String compressionType;

    public CSVInput getCsv() {
      return csv;
    }

    public void setCsv(CSVInput csv) {
      this.csv = csv;
    }

    public JSONInput getJson() {
      return json;
    }

    public void setJson(JSONInput json) {
      this.json = json;
    }

    public ParquetInput getParquet() {
      return parquet;
    }

    public void setParquet(ParquetInput parquet) {
      this.parquet = parquet;
    }

    public String getCompressionType() {
      return compressionType;
    }

    public void setCompressionType(String compressionType) {
      this.compressionType = compressionType;
    }
  }

  /**
   * Output serialization configuration.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class OutputSerialization {
    @XmlElement(name = "CSV")
    private CSVOutput csv;

    @XmlElement(name = "JSON")
    private JSONOutput json;

    public CSVOutput getCsv() {
      return csv;
    }

    public void setCsv(CSVOutput csv) {
      this.csv = csv;
    }

    public JSONOutput getJson() {
      return json;
    }

    public void setJson(JSONOutput json) {
      this.json = json;
    }
  }

  /**
   * CSV input configuration.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class CSVInput {
    @XmlElement(name = "FileHeaderInfo")
    private String fileHeaderInfo;

    @XmlElement(name = "Comments")
    private String comments;

    @XmlElement(name = "QuoteEscapeCharacter")
    private String quoteEscapeCharacter;

    @XmlElement(name = "RecordDelimiter")
    private String recordDelimiter;

    @XmlElement(name = "FieldDelimiter")
    private String fieldDelimiter;

    @XmlElement(name = "QuoteCharacter")
    private String quoteCharacter;

    @XmlElement(name = "AllowQuotedRecordDelimiter")
    private Boolean allowQuotedRecordDelimiter;

    public String getFileHeaderInfo() {
      return fileHeaderInfo;
    }

    public void setFileHeaderInfo(String fileHeaderInfo) {
      this.fileHeaderInfo = fileHeaderInfo;
    }

    public String getComments() {
      return comments;
    }

    public void setComments(String comments) {
      this.comments = comments;
    }

    public String getQuoteEscapeCharacter() {
      return quoteEscapeCharacter;
    }

    public void setQuoteEscapeCharacter(String quoteEscapeCharacter) {
      this.quoteEscapeCharacter = quoteEscapeCharacter;
    }

    public String getRecordDelimiter() {
      return recordDelimiter;
    }

    public void setRecordDelimiter(String recordDelimiter) {
      this.recordDelimiter = recordDelimiter;
    }

    public String getFieldDelimiter() {
      return fieldDelimiter;
    }

    public void setFieldDelimiter(String fieldDelimiter) {
      this.fieldDelimiter = fieldDelimiter;
    }

    public String getQuoteCharacter() {
      return quoteCharacter;
    }

    public void setQuoteCharacter(String quoteCharacter) {
      this.quoteCharacter = quoteCharacter;
    }

    public Boolean getAllowQuotedRecordDelimiter() {
      return allowQuotedRecordDelimiter;
    }

    public void setAllowQuotedRecordDelimiter(Boolean allowQuotedRecordDelimiter) {
      this.allowQuotedRecordDelimiter = allowQuotedRecordDelimiter;
    }
  }

  /**
   * CSV output configuration.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class CSVOutput {
    @XmlElement(name = "QuoteFields")
    private String quoteFields;

    @XmlElement(name = "QuoteEscapeCharacter")
    private String quoteEscapeCharacter;

    @XmlElement(name = "RecordDelimiter")
    private String recordDelimiter;

    @XmlElement(name = "FieldDelimiter")
    private String fieldDelimiter;

    @XmlElement(name = "QuoteCharacter")
    private String quoteCharacter;

    public String getQuoteFields() {
      return quoteFields;
    }

    public void setQuoteFields(String quoteFields) {
      this.quoteFields = quoteFields;
    }

    public String getQuoteEscapeCharacter() {
      return quoteEscapeCharacter;
    }

    public void setQuoteEscapeCharacter(String quoteEscapeCharacter) {
      this.quoteEscapeCharacter = quoteEscapeCharacter;
    }

    public String getRecordDelimiter() {
      return recordDelimiter;
    }

    public void setRecordDelimiter(String recordDelimiter) {
      this.recordDelimiter = recordDelimiter;
    }

    public String getFieldDelimiter() {
      return fieldDelimiter;
    }

    public void setFieldDelimiter(String fieldDelimiter) {
      this.fieldDelimiter = fieldDelimiter;
    }

    public String getQuoteCharacter() {
      return quoteCharacter;
    }

    public void setQuoteCharacter(String quoteCharacter) {
      this.quoteCharacter = quoteCharacter;
    }
  }

  /**
   * JSON input configuration.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class JSONInput {
    @XmlElement(name = "Type")
    private String type;

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }
  }

  /**
   * JSON output configuration.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class JSONOutput {
    @XmlElement(name = "RecordDelimiter")
    private String recordDelimiter;

    public String getRecordDelimiter() {
      return recordDelimiter;
    }

    public void setRecordDelimiter(String recordDelimiter) {
      this.recordDelimiter = recordDelimiter;
    }
  }

  /**
   * Parquet input configuration.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class ParquetInput {
  }

  /**
   * Scan range configuration for partial object queries.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  public static class ScanRange {
    @XmlElement(name = "Start")
    private Long start;

    @XmlElement(name = "End")
    private Long end;

    public Long getStart() {
      return start;
    }

    public void setStart(Long start) {
      this.start = start;
    }

    public Long getEnd() {
      return end;
    }

    public void setEnd(Long end) {
      this.end = end;
    }
  }
}
