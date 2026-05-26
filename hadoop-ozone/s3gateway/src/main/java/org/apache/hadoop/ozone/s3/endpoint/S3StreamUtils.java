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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Stream copy helpers for S3 Gateway request hot paths.
 */
final class S3StreamUtils {

  private static final int MAX_RETAINED_BUFFER_SIZE = 4 * 1024 * 1024;
  private static final int MAX_RETAINED_BUFFERS = Math.max(8,
      Math.min(64, Runtime.getRuntime().availableProcessors() * 4));
  private static final BlockingQueue<byte[]> COPY_BUFFERS =
      new ArrayBlockingQueue<>(MAX_RETAINED_BUFFERS);

  private S3StreamUtils() {
  }

  static long copy(InputStream source, OutputStream target, long length,
      int bufferSize) throws IOException {
    if (length < 0) {
      throw new IllegalArgumentException("length must not be negative");
    }
    if (bufferSize <= 0) {
      throw new IllegalArgumentException("bufferSize must be positive");
    }
    if (length == 0) {
      return 0;
    }

    byte[] buffer = acquireBuffer(bufferSize);
    try {
      long remaining = length;
      long copied = 0;
      while (remaining > 0) {
        int bytesToRead = (int) Math.min(buffer.length, remaining);
        int bytesRead = source.read(buffer, 0, bytesToRead);
        if (bytesRead < 0) {
          break;
        }
        if (bytesRead == 0) {
          int nextByte = source.read();
          if (nextByte < 0) {
            break;
          }
          target.write(nextByte);
          bytesRead = 1;
        } else {
          target.write(buffer, 0, bytesRead);
        }
        copied += bytesRead;
        remaining -= bytesRead;
      }
      return copied;
    } finally {
      releaseBuffer(buffer);
    }
  }

  private static byte[] acquireBuffer(int bufferSize) {
    byte[] buffer = COPY_BUFFERS.poll();
    if (buffer == null || buffer.length < bufferSize) {
      return new byte[bufferSize];
    }
    return buffer;
  }

  private static void releaseBuffer(byte[] buffer) {
    if (buffer.length <= MAX_RETAINED_BUFFER_SIZE) {
      COPY_BUFFERS.offer(buffer);
    }
  }

  static void clearBuffersForTesting() {
    COPY_BUFFERS.clear();
  }

  static int retainedBufferCountForTesting() {
    return COPY_BUFFERS.size();
  }
}
