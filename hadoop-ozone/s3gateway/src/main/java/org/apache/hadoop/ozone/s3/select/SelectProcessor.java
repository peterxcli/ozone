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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import org.apache.arrow.datafusion.DataFrame;
import org.apache.arrow.datafusion.SessionContext;
import org.apache.arrow.datafusion.SessionContexts;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor for S3 Select queries using DataFusion.
 */
public class SelectProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SelectProcessor.class);
  
  private final OzoneClient client;
  private final OzoneBucket bucket;
  private final String keyPath;

  public SelectProcessor(OzoneClient client, OzoneBucket bucket, String keyPath) {
    this.client = client;
    this.bucket = bucket;
    this.keyPath = keyPath;
  }

  public void processSelect(SelectObjectContentRequest request, OutputStream output) 
      throws Exception {
    
    Path tempFile = Files.createTempFile("ozone-select-", ".tmp");
    
    try {
      downloadObjectToTempFile(tempFile);
      
      executeSelectQuery(tempFile, request, output);
      
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  private void downloadObjectToTempFile(Path tempFile) throws IOException {
    try (OzoneInputStream ozoneInputStream = bucket.readKey(keyPath);
         InputStream inputStream = ozoneInputStream) {
      Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void executeSelectQuery(Path dataFile, SelectObjectContentRequest request, 
                                   OutputStream output) throws Exception {
    
    try (SessionContext context = SessionContexts.create();
         BufferAllocator allocator = new RootAllocator()) {
      
      String tableName = registerTable(context, dataFile, request);
      
      String sqlQuery = convertS3SelectToSQL(request.getExpression(), tableName);
      
      CompletableFuture<DataFrame> dataFrameFuture = context.sql(sqlQuery);
      DataFrame dataFrame = dataFrameFuture.join();
      
      writeResultsToOutput(dataFrame, allocator, request, output);
    }
  }

  private String registerTable(SessionContext context, Path dataFile, 
                               SelectObjectContentRequest request) throws Exception {
    String tableName = "s3_select_table";
    
    if (request.getInputSerialization().getCsv() != null) {
      context.registerCsv(tableName, dataFile).join();
    } else if (request.getInputSerialization().getJson() != null) {
      // For JSON, we'll use CSV reader with specific settings
      // DataFusion Java doesn't have registerJson yet, so we'll treat it as CSV for now
      context.registerCsv(tableName, dataFile).join();
    } else if (request.getInputSerialization().getParquet() != null) {
      context.registerParquet(tableName, dataFile).join();
    } else {
      throw new IllegalArgumentException("Unsupported input serialization format");
    }
    
    return tableName;
  }

  private String convertS3SelectToSQL(String s3SelectExpression, String tableName) {
    String sql = s3SelectExpression;
    
    sql = sql.replaceAll("(?i)FROM\\s+S3Object", "FROM " + tableName);
    sql = sql.replaceAll("(?i)S3Object\\.", tableName + ".");
    
    sql = sql.replaceAll("(?i)_\\d+", "column$0");
    
    return sql;
  }

  private void writeResultsToOutput(DataFrame dataFrame, BufferAllocator allocator,
                                    SelectObjectContentRequest request, 
                                    OutputStream output) throws Exception {
    
    SelectObjectContentResponse response = new SelectObjectContentResponse(output);
    
    response.writeRecordsEvent(() -> {
      try (ArrowReader reader = dataFrame.collect(allocator).join()) {
        VectorSchemaRoot root = reader.getVectorSchemaRoot();
        
        while (reader.loadNextBatch()) {
          if (request.getOutputSerialization().getCsv() != null) {
            writeCSVOutput(root, request.getOutputSerialization().getCsv(), output);
          } else if (request.getOutputSerialization().getJson() != null) {
            writeJSONOutput(root, request.getOutputSerialization().getJson(), output);
          }
        }
      } catch (Exception e) {
        LOG.error("Error writing results", e);
        throw new RuntimeException(e);
      }
    });
    
    response.writeStatsEvent(0, 0, 0);
    
    response.writeEndEvent();
  }

  private void writeCSVOutput(VectorSchemaRoot root, 
                              SelectObjectContentRequest.CSVOutput csvOutput,
                              OutputStream output) throws IOException {
    String delimiter = csvOutput.getFieldDelimiter() != null ? 
        csvOutput.getFieldDelimiter() : ",";
    String recordDelimiter = csvOutput.getRecordDelimiter() != null ? 
        csvOutput.getRecordDelimiter() : "\n";
    
    for (int row = 0; row < root.getRowCount(); row++) {
      StringBuilder rowBuilder = new StringBuilder();
      for (int col = 0; col < root.getFieldVectors().size(); col++) {
        if (col > 0) {
          rowBuilder.append(delimiter);
        }
        Object value = root.getFieldVectors().get(col).getObject(row);
        if (value != null) {
          rowBuilder.append(value.toString());
        }
      }
      rowBuilder.append(recordDelimiter);
      output.write(rowBuilder.toString().getBytes());
    }
  }

  private void writeJSONOutput(VectorSchemaRoot root,
                               SelectObjectContentRequest.JSONOutput jsonOutput,
                               OutputStream output) throws IOException {
    String recordDelimiter = jsonOutput.getRecordDelimiter() != null ?
        jsonOutput.getRecordDelimiter() : "\n";
    
    for (int row = 0; row < root.getRowCount(); row++) {
      StringBuilder jsonBuilder = new StringBuilder("{");
      for (int col = 0; col < root.getFieldVectors().size(); col++) {
        if (col > 0) {
          jsonBuilder.append(",");
        }
        String fieldName = root.getSchema().getFields().get(col).getName();
        Object value = root.getFieldVectors().get(col).getObject(row);
        
        jsonBuilder.append("\"").append(fieldName).append("\":");
        if (value == null) {
          jsonBuilder.append("null");
        } else if (value instanceof String) {
          jsonBuilder.append("\"").append(value).append("\"");
        } else {
          jsonBuilder.append(value);
        }
      }
      jsonBuilder.append("}").append(recordDelimiter);
      output.write(jsonBuilder.toString().getBytes());
    }
  }
}
