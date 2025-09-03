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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * S3 Select response formatter that creates S3-compatible event stream messages.
 */
public class SelectObjectContentResponse {
  private static final byte[] RECORDS_EVENT_TYPE = ":event-type".getBytes(StandardCharsets.UTF_8);
  private static final byte[] RECORDS_EVENT_NAME = "Records".getBytes(StandardCharsets.UTF_8);
  private static final byte[] STATS_EVENT_NAME = "Stats".getBytes(StandardCharsets.UTF_8);
  private static final byte[] END_EVENT_NAME = "End".getBytes(StandardCharsets.UTF_8);
  private static final byte[] MESSAGE_TYPE = ":message-type".getBytes(StandardCharsets.UTF_8);
  private static final byte[] EVENT_TYPE = "event".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CONTENT_TYPE_HEADER = ":content-type".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OCTET_STREAM = "application/octet-stream".getBytes(StandardCharsets.UTF_8);

  private final OutputStream outputStream;

  public SelectObjectContentResponse(OutputStream outputStream) {
    this.outputStream = outputStream;
  }

  public void writeRecordsEvent(RecordsWriter writer) throws IOException {
    ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
    try {
      writer.write();
    } catch (Exception e) {
      throw new IOException("Error writing records", e);
    }
    byte[] payload = payloadStream.toByteArray();

    writeEvent(RECORDS_EVENT_NAME, payload);
  }

  public void writeStatsEvent(long bytesScanned, long bytesProcessed, long bytesReturned) 
      throws IOException {
    String statsXml = String.format(
        "<Stats><BytesScanned>%d</BytesScanned>" +
        "<BytesProcessed>%d</BytesProcessed>" +
        "<BytesReturned>%d</BytesReturned></Stats>",
        bytesScanned, bytesProcessed, bytesReturned);
    
    writeEvent(STATS_EVENT_NAME, statsXml.getBytes(StandardCharsets.UTF_8));
  }

  public void writeEndEvent() throws IOException {
    writeEvent(END_EVENT_NAME, new byte[0]);
  }

  private void writeEvent(byte[] eventName, byte[] payload) throws IOException {
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    
    writeHeader(headers, RECORDS_EVENT_TYPE, eventName);
    writeHeader(headers, MESSAGE_TYPE, EVENT_TYPE);
    if (payload.length > 0) {
      writeHeader(headers, CONTENT_TYPE_HEADER, OCTET_STREAM);
    }

    byte[] headerBytes = headers.toByteArray();
    int totalLength = 12 + headerBytes.length + payload.length + 4;

    ByteBuffer prelude = ByteBuffer.allocate(12);
    prelude.putInt(totalLength);
    prelude.putInt(headerBytes.length);
    prelude.putInt(calculateCRC32(prelude.array(), 0, 8));

    outputStream.write(prelude.array());
    outputStream.write(headerBytes);
    outputStream.write(payload);

    ByteBuffer messageCrc = ByteBuffer.allocate(4);
    messageCrc.putInt(calculateCRC32(totalLength, headerBytes, payload));
    outputStream.write(messageCrc.array());

    outputStream.flush();
  }

  private void writeHeader(ByteArrayOutputStream stream, byte[] name, byte[] value) 
      throws IOException {
    stream.write((byte) name.length);
    stream.write(name);
    stream.write((byte) 7);
    stream.write(ByteBuffer.allocate(2).putShort((short) value.length).array());
    stream.write(value);
  }

  private int calculateCRC32(byte[] data, int offset, int length) {
    CRC32 crc32 = new CRC32();
    crc32.update(data, offset, length);
    return (int) crc32.getValue();
  }

  private int calculateCRC32(int totalLength, byte[] headers, byte[] payload) {
    CRC32 crc32 = new CRC32();
    
    ByteBuffer prelude = ByteBuffer.allocate(8);
    prelude.putInt(totalLength);
    prelude.putInt(headers.length);
    crc32.update(prelude.array());
    
    crc32.update(headers);
    crc32.update(payload);
    
    return (int) crc32.getValue();
  }

  /**
   * Interface for writing records to the response stream.
   */
  public interface RecordsWriter {
    void write() throws Exception;
  }
}
