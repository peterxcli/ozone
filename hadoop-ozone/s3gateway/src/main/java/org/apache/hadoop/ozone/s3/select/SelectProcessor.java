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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.arrow.datafusion.DataFrame;
import org.apache.arrow.datafusion.SessionContext;
import org.apache.arrow.datafusion.SessionContexts;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneKeyDetails;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor for S3 Select queries using DataFusion.
 */
public class SelectProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SelectProcessor.class);
  
  private final OzoneBucket bucket;
  private final String keyPath;

  public SelectProcessor(OzoneBucket bucket, String keyPath) {
    this.bucket = bucket;
    this.keyPath = keyPath;
  }

  public void processSelect(SelectObjectContentRequest request, OutputStream output) 
      throws Exception {
    
    Path tempFile = Files.createTempFile("ozone-select-", ".tmp");
    
    try {
      // For Parquet files, try to optimize the download
      if (request.getInputSerialization().getParquet() != null) {
        processParquetWithOptimization(tempFile, request, output);
      } else {
        // For CSV/JSON, download the entire file
        downloadObjectToTempFile(tempFile);
        executeSelectQuery(tempFile, request, output);
      }
      
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

  private void processParquetWithOptimization(Path tempFile, 
                                               SelectObjectContentRequest request,
                                               OutputStream output) throws Exception {
    // First, download just the Parquet metadata (footer)
    OzoneKeyDetails keyDetails = bucket.getKey(keyPath);
    long fileSize = keyDetails.getDataSize();
    
    // Parquet footer is typically at the end of file
    // Start by reading last 8 bytes to get footer size
    long footerReadStart = Math.max(0, fileSize - 65536); // Read last 64KB for footer
    
    Path metadataFile = Files.createTempFile("parquet-meta-", ".tmp");
    try {
      // Download the footer portion
      downloadPartialObject(footerReadStart, fileSize - 1, metadataFile);
      
      // Parse the footer to identify row groups and columns
      ParquetMetadata metadata = readParquetMetadata(metadataFile, fileSize);
      
      // Extract predicates and required columns from SQL
      QueryAnalysis analysis = analyzeQuery(request.getExpression());
      
      // Determine which row groups to read based on statistics
      List<RowGroupRange> rowGroupsToRead = selectRowGroups(metadata, analysis);
      
      if (rowGroupsToRead.isEmpty()) {
        // No matching data, return empty result
        writeEmptyResult(request, output);
        return;
      }
      
      // Download only the required row groups
      downloadRowGroups(rowGroupsToRead, tempFile);
      
      // Execute query on the partial file
      executeSelectQuery(tempFile, request, output);
      
    } finally {
      Files.deleteIfExists(metadataFile);
    }
  }

  private void downloadPartialObject(long start, long end, Path targetFile) 
      throws IOException {
    // Use range requests to download partial content
    try (OzoneInputStream ozoneInputStream = bucket.readKey(keyPath);
         InputStream inputStream = ozoneInputStream;
         SeekableByteChannel channel = Files.newByteChannel(targetFile, 
             StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      
      // Skip to start position
      long skipped = inputStream.skip(start);
      if (skipped < start) {
        throw new IOException("Could not skip to position " + start);
      }
      
      // Read only the requested range
      long bytesToRead = end - start + 1;
      byte[] buffer = new byte[8192];
      long totalRead = 0;
      
      while (totalRead < bytesToRead) {
        int toRead = (int) Math.min(buffer.length, bytesToRead - totalRead);
        int read = inputStream.read(buffer, 0, toRead);
        if (read == -1) {
          break;
        }
        channel.write(ByteBuffer.wrap(buffer, 0, read));
        totalRead += read;
      }
    }
  }

  private ParquetMetadata readParquetMetadata(Path metadataFile, long fileSize) {
    // Simplified - in reality, we'd parse the Parquet footer
    // This would use Parquet libraries to read metadata
    return new ParquetMetadata();
  }

  private QueryAnalysis analyzeQuery(String sql) {
    QueryAnalysis analysis = new QueryAnalysis();
    
    // Extract SELECT columns
    Pattern selectPattern = Pattern.compile("SELECT\\s+(.+?)\\s+FROM", Pattern.CASE_INSENSITIVE);
    Matcher selectMatcher = selectPattern.matcher(sql);
    if (selectMatcher.find()) {
      String selectClause = selectMatcher.group(1);
      if (!selectClause.trim().equals("*")) {
        analysis.setRequiredColumns(parseColumns(selectClause));
      }
    }
    
    // Extract WHERE predicates
    Pattern wherePattern = Pattern.compile("WHERE\\s+(.+?)(?:\\s+ORDER|\\s+GROUP|\\s+LIMIT|$)", 
                                          Pattern.CASE_INSENSITIVE);
    Matcher whereMatcher = wherePattern.matcher(sql);
    if (whereMatcher.find()) {
      analysis.setPredicates(whereMatcher.group(1));
    }
    
    return analysis;
  }

  private List<String> parseColumns(String selectClause) {
    List<String> columns = new ArrayList<>();
    String[] parts = selectClause.split(",");
    for (String part : parts) {
      columns.add(part.trim());
    }
    return columns;
  }

  private List<RowGroupRange> selectRowGroups(ParquetMetadata metadata, 
                                               QueryAnalysis analysis) {
    // Use column statistics to filter row groups
    // This would check min/max values against predicates
    List<RowGroupRange> ranges = new ArrayList<>();
    // Simplified implementation
    ranges.add(new RowGroupRange(0, metadata.getFileSize()));
    return ranges;
  }

  private void downloadRowGroups(List<RowGroupRange> rowGroups, Path targetFile) 
      throws IOException {
    try (SeekableByteChannel channel = Files.newByteChannel(targetFile,
             StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      
      for (RowGroupRange range : rowGroups) {
        downloadPartialObject(range.getStart(), range.getEnd(), targetFile);
      }
    }
  }

  private void writeEmptyResult(SelectObjectContentRequest request, 
                                OutputStream output) throws IOException {
    SelectObjectContentResponse response = new SelectObjectContentResponse(output);
    response.writeRecordsEvent(() -> {
      // Write empty result
    });
    response.writeStatsEvent(0, 0, 0);
    response.writeEndEvent();
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
      StringBuilder jsonBuilder = new StringBuilder();
      jsonBuilder.append('{');
      for (int col = 0; col < root.getFieldVectors().size(); col++) {
        if (col > 0) {
          jsonBuilder.append(',');
        }
        String fieldName = root.getSchema().getFields().get(col).getName();
        Object value = root.getFieldVectors().get(col).getObject(row);
        
        jsonBuilder.append('"').append(fieldName).append('"').append(':');
        if (value == null) {
          jsonBuilder.append("null");
        } else if (value instanceof String) {
          jsonBuilder.append('"').append(value).append('"');
        } else {
          jsonBuilder.append(value);
        }
      }
      jsonBuilder.append('}').append(recordDelimiter);
      output.write(jsonBuilder.toString().getBytes());
    }
  }

  /**
   * Helper class to store Parquet metadata.
   */
  private static class ParquetMetadata {
    private long fileSize;
    
    public long getFileSize() {
      return fileSize;
    }
    
    public void setFileSize(long fileSize) {
      this.fileSize = fileSize;
    }
  }

  /**
   * Helper class to store query analysis results.
   */
  private static class QueryAnalysis {
    private List<String> requiredColumns;
    private String predicates;
    
    public List<String> getRequiredColumns() {
      return requiredColumns;
    }
    
    public void setRequiredColumns(List<String> columns) {
      this.requiredColumns = columns;
    }
    
    public String getPredicates() {
      return predicates;
    }
    
    public void setPredicates(String predicates) {
      this.predicates = predicates;
    }
  }

  /**
   * Helper class to store row group byte ranges.
   */
  private static class RowGroupRange {
    private final long start;
    private final long end;
    
    RowGroupRange(long start, long end) {
      this.start = start;
      this.end = end;
    }
    
    public long getStart() {
      return start;
    }
    
    public long getEnd() {
      return end;
    }
  }
}
