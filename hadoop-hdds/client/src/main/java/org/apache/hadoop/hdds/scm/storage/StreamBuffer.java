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

package org.apache.hadoop.hdds.scm.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Used for streaming write.
 */
public class StreamBuffer {
  private final ByteBuffer buffer;

  public static final class InputStreamReadException extends IOException {
    private static final long serialVersionUID = 1L;

    private InputStreamReadException(IOException cause) {
      super(cause);
    }

    public IOException getIOException() {
      return (IOException) getCause();
    }
  }

  public StreamBuffer(ByteBuffer buffer) {
    this.buffer = buffer;
  }

  public StreamBuffer(ByteBuffer buffer, int offset, int length) {
    this((ByteBuffer) buffer.asReadOnlyBuffer().position(offset)
        .limit(offset + length));
  }

  public ByteBuffer duplicate() {
    return buffer.duplicate();
  }

  public int remaining() {
    return buffer.remaining();
  }

  public int position() {
    return buffer.position();
  }

  public void put(StreamBuffer sb) {
    buffer.put(sb.buffer);
  }

  public int readFrom(InputStream in, int length) throws IOException {
    final int readLength = Math.min(length, remaining());
    if (readLength == 0) {
      return 0;
    }
    if (!buffer.hasArray()) {
      throw new IOException("StreamBuffer does not have an accessible array");
    }
    final int position = buffer.position();
    final int bytesRead;
    try {
      bytesRead = in.read(buffer.array(),
          buffer.arrayOffset() + position, readLength);
    } catch (IOException ioe) {
      throw new InputStreamReadException(ioe);
    }
    if (bytesRead > 0) {
      buffer.position(position + bytesRead);
    }
    return bytesRead;
  }

  public static StreamBuffer allocate(int size) {
    return new StreamBuffer(ByteBuffer.allocate(size));
  }

}
