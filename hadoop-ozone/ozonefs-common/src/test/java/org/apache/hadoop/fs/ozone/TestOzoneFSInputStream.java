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

package org.apache.hadoop.fs.ozone;

import static org.apache.hadoop.hdds.scm.storage.PositionedReadTestHelper.SOURCE_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.apache.commons.lang3.RandomUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CipherSuite;
import org.apache.hadoop.crypto.CryptoCodec;
import org.apache.hadoop.crypto.CryptoInputStream;
import org.apache.hadoop.crypto.Decryptor;
import org.apache.hadoop.fs.ByteBufferPositionedReadable;
import org.apache.hadoop.fs.FileRange;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.fs.StreamCapabilities;
import org.apache.hadoop.hdds.scm.storage.ByteReaderStrategy;
import org.apache.hadoop.hdds.scm.storage.ExtendedInputStream;
import org.apache.hadoop.hdds.scm.storage.PositionedReadTestHelper;
import org.apache.hadoop.ozone.client.io.KeyInputStream;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link OzoneFSInputStream}.
 */
public class TestOzoneFSInputStream {

  private static final byte CORRUPT_BYTE = (byte) 0x5A;

  private static final List<IntFunction<ByteBuffer>> BUFFER_CONSTRUCTORS =
      ImmutableList.of(ByteBuffer::allocate, ByteBuffer::allocateDirect);

  @Test
  public void readToByteBuffer() throws IOException {
    for (IntFunction<ByteBuffer> constructor : BUFFER_CONSTRUCTORS) {
      for (int streamLength = 1; streamLength <= 10; streamLength++) {
        for (int bufferCapacity = 0; bufferCapacity <= 10; bufferCapacity++) {
          testReadToByteBuffer(constructor, streamLength, bufferCapacity, 0);
          if (bufferCapacity > 1) {
            testReadToByteBuffer(constructor, streamLength, bufferCapacity, 1);
            if (bufferCapacity > 2) {
              testReadToByteBuffer(constructor, streamLength, bufferCapacity,
                  bufferCapacity - 1);
            }
          }
          testReadToByteBuffer(constructor, streamLength, bufferCapacity,
              bufferCapacity);
        }
      }
    }
  }

  private static void testReadToByteBuffer(
      IntFunction<ByteBuffer> bufferConstructor,
      int streamLength, int bufferCapacity,
      int bufferPosition) throws IOException {
    final byte[] source = RandomUtils.secure().randomBytes(streamLength);
    final InputStream input = new ByteArrayInputStream(source);
    final OzoneFSInputStream subject = createTestSubject(input);

    final int expectedReadLength = Math.min(bufferCapacity - bufferPosition,
        input.available());
    final byte[] expectedContent = Arrays.copyOfRange(source, 0,
        expectedReadLength);

    final ByteBuffer buf = bufferConstructor.apply(bufferCapacity);
    buf.position(bufferPosition);

    final int bytesRead = subject.read(buf);

    assertEquals(expectedReadLength, bytesRead);

    final byte[] content = new byte[bytesRead];
    buf.position(bufferPosition);
    buf.get(content);
    assertArrayEquals(expectedContent, content);
  }

  @Test
  public void readEmptyStreamToByteBuffer() throws IOException {
    for (IntFunction<ByteBuffer> constructor : BUFFER_CONSTRUCTORS) {
      final OzoneFSInputStream subject = createTestSubject(emptyStream());
      final ByteBuffer buf = constructor.apply(1);

      final int bytesRead = subject.read(buf);

      assertEquals(-1, bytesRead);
      assertEquals(0, buf.position());
    }
  }

  @Test
  public void bufferPositionUnchangedOnEOF() throws IOException {
    for (IntFunction<ByteBuffer> constructor : BUFFER_CONSTRUCTORS) {
      final OzoneFSInputStream subject = createTestSubject(eofStream());
      final ByteBuffer buf = constructor.apply(123);

      final int bytesRead = subject.read(buf);

      assertEquals(-1, bytesRead);
      assertEquals(0, buf.position());
    }
  }

  @Test
  public void testStreamCapability() throws IOException {
    final OzoneFSInputStream subject = createTestSubject(emptyStream());
    CapableOzoneFSInputStream capableOzoneFSInputStream = null;
    try {
      capableOzoneFSInputStream = new CapableOzoneFSInputStream(subject,
          new FileSystem.Statistics("test"));

      assertTrue(capableOzoneFSInputStream.
          hasCapability(StreamCapabilities.READBYTEBUFFER));
      assertTrue(capableOzoneFSInputStream.hasCapability(StreamCapabilities.VECTOREDIO));
    } finally {
      if (capableOzoneFSInputStream != null) {
        capableOzoneFSInputStream.close();
      }
    }
  }

  @Test
  @Timeout(20)
  void vectoredReadsOverlapAndAllocateOnlyForActiveWorkers() throws Exception {
    ExtendedInputStream input = mock(ExtendedInputStream.class);
    CountDownLatch started = new CountDownLatch(4);
    CountDownLatch proceed = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    AtomicInteger allocations = new AtomicInteger();
    when(input.read(anyLong(), any(ByteBuffer.class))).thenAnswer(inv -> {
      started.countDown();
      assertTrue(proceed.await(10, TimeUnit.SECONDS));
      long offset = inv.getArgument(0);
      ByteBuffer buffer = inv.getArgument(1);
      int length = buffer.remaining();
      for (int i = 0; i < length; i++) {
        buffer.put((byte) (offset + i));
      }
      return length;
    });
    List<FileRange> ranges = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      ranges.add(FileRange.createFileRange(i * 8, 4));
    }
    FileSystem.Statistics statistics = new FileSystem.Statistics("vector");
    try (CapableOzoneFSInputStream stream = new CapableOzoneFSInputStream(input, statistics)) {
      stream.readVectored(ranges, length -> {
        allocations.incrementAndGet();
        return ByteBuffer.allocateDirect(length);
      }, buffer -> released.countDown());
      assertTrue(started.await(5, TimeUnit.SECONDS), "four reads must overlap");
      assertEquals(4, allocations.get());
      for (FileRange range : ranges) {
        assertNotNull(range.getData());
        assertFalse(range.getData().isDone());
      }
      // Cancellation while a read is active must return its allocated buffer.
      ranges.get(0).getData().cancel(false);
      proceed.countDown();
      for (int i = 1; i < ranges.size(); i++) {
        ByteBuffer data = ranges.get(i).getData().get(5, TimeUnit.SECONDS);
        assertEquals(4, data.remaining());
        for (int j = 0; j < 4; j++) {
          assertEquals((byte) (i * 8 + j), data.get());
        }
      }
      assertTrue(released.await(5, TimeUnit.SECONDS));
      assertEquals(80, statistics.getBytesRead());
    } finally {
      proceed.countDown();
    }
  }

  @Test
  void vectoredReadsPreserveFallbackCursorAndReleaseFailedBuffers() throws Exception {
    byte[] source = RandomUtils.secure().randomBytes(32);
    for (IntFunction<ByteBuffer> allocate : BUFFER_CONSTRUCTORS) {
      AtomicInteger released = new AtomicInteger();
      FileRange success = FileRange.createFileRange(3, 4);
      FileRange empty = FileRange.createFileRange(12, 0);
      FileRange eof = FileRange.createFileRange(28, 8);
      try (CapableOzoneFSInputStream stream = new CapableOzoneFSInputStream(
          new SeekableOnlyInputStream(source), null)) {
        stream.seek(17);
        stream.readVectored(Arrays.asList(eof, empty, success), allocate, buffer -> {
          released.incrementAndGet();
          throw new IllegalStateException("release failed");
        });
        ByteBuffer data = success.getData().get(5, TimeUnit.SECONDS);
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        assertArrayEquals(Arrays.copyOfRange(source, 3, 7), bytes);
        assertEquals(0, empty.getData().get(5, TimeUnit.SECONDS).remaining());
        ExecutionException error = assertThrows(ExecutionException.class, () -> eof.getData().get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof EOFException);
        assertEquals(1, error.getCause().getSuppressed().length);
        assertEquals(1, released.get());
        assertEquals(17, stream.getPos());
      }
    }
  }

  @Test
  void vectoredReadsValidateBeforeSchedulingAndReportAllocationFailure() throws Exception {
    try (CapableOzoneFSInputStream stream = new CapableOzoneFSInputStream(new NativePositionedInputStream(new byte[32]),
        null)) {
      assertThrows(IllegalArgumentException.class, () -> stream.readVectored(
          Arrays.asList(FileRange.createFileRange(0, 4), FileRange.createFileRange(3, 4)), ByteBuffer::allocate));
      assertThrows(EOFException.class, () -> stream.readVectored(
          Collections.singletonList(FileRange.createFileRange(-1, 4)), ByteBuffer::allocate));
      stream.readVectored(Collections.emptyList(), ByteBuffer::allocate);
      FileRange failed = FileRange.createFileRange(0, 4);
      FileRange success = FileRange.createFileRange(10, 2);
      stream.readVectored(Arrays.asList(failed, success), length -> {
        if (length == 4) {
          throw new IllegalStateException("allocation failed");
        }
        return ByteBuffer.allocate(length);
      });
      assertThrows(ExecutionException.class, () -> failed.getData().get(5, TimeUnit.SECONDS));
      assertEquals(2, success.getData().get(5, TimeUnit.SECONDS).remaining());
    }
  }

  @Test
  @Timeout(20)
  void closingStreamFailsPendingVectoredRangesWithoutAllocatingBuffers() throws Exception {
    CountDownLatch started = new CountDownLatch(4);
    CountDownLatch proceed = new CountDownLatch(1);
    AtomicInteger allocations = new AtomicInteger();
    ExtendedInputStream input = mock(ExtendedInputStream.class);
    when(input.read(anyLong(), any(ByteBuffer.class))).thenAnswer(inv -> {
      started.countDown();
      assertTrue(proceed.await(10, TimeUnit.SECONDS));
      throw new IOException("Stream is closed");
    });
    List<FileRange> ranges = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      ranges.add(FileRange.createFileRange(i, 1));
    }
    try (CapableOzoneFSInputStream stream = new CapableOzoneFSInputStream(input, null)) {
      stream.readVectored(ranges, length -> {
        allocations.incrementAndGet();
        return ByteBuffer.allocate(length);
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      stream.close();
      proceed.countDown();
      for (FileRange range : ranges) {
        assertThrows(ExecutionException.class, () -> range.getData().get(5, TimeUnit.SECONDS));
      }
      assertEquals(4, allocations.get());
      assertThrows(IOException.class, () -> stream.readVectored(ranges, ByteBuffer::allocate));
    } finally {
      proceed.countDown();
    }
  }

  @Test
  public void testCryptoStreamUnbuffer()
      throws IOException, GeneralSecurityException {
    KeyInputStream keyInputStream = mock(KeyInputStream.class);
    when(keyInputStream.hasCapability(anyString())).thenReturn(true);

    CryptoCodec codec = mock(CryptoCodec.class);
    when(codec.getCipherSuite()).thenReturn(CipherSuite.AES_CTR_NOPADDING);
    when(codec.getConf()).thenReturn(new Configuration());
    Decryptor decryptor = mock(Decryptor.class);
    when(codec.createDecryptor()).thenReturn(decryptor);
    CryptoInputStream cis = new CryptoInputStream(keyInputStream, codec,
        new byte[0], new byte[0]);
    try {
      cis.unbuffer();
      verify(keyInputStream, times(1)).unbuffer();
    } finally {
      cis.close();
    }
  }

  private static OzoneFSInputStream createTestSubject(InputStream input) {
    return new OzoneFSInputStream(input,
        new FileSystem.Statistics("test"));
  }

  private static InputStream emptyStream() {
    return new ByteArrayInputStream(new byte[0]);
  }

  private static InputStream eofStream() {
    return new InputStream() {
      @Override
      public int available() {
        return 123;
      }

      @Override
      public int read() {
        return -1;
      }
    };
  }

  @Test
  public void testByteBufferPositionedReadNegativePositionThrows() throws Exception {
    // read(long, ByteBuffer) must throw EOFException for negative positions,
    // aligning with the byte-array PositionedReadable behaviour.
    final byte[] source = RandomUtils.secure().randomBytes(SOURCE_SIZE);
    final InterleavingSeekableInputStream underlying =
        new InterleavingSeekableInputStream(source);
    try (OzoneFSInputStream subject = new OzoneFSInputStream(underlying,
        new FileSystem.Statistics("test"))) {
      ByteBuffer buf = ByteBuffer.allocate(16);
      assertThrows(EOFException.class, () -> subject.read(-1L, buf));
    }
  }

  @Test
  @Timeout(value = 30)
  public void testByteArrayFallbackWorksForSeekableOnlyStream() throws Exception {
    // Regression: the old routing through read(long, ByteBuffer) would cast to
    // ByteBufferReadable in readAtPositionSeekRestore and throw ClassCastException
    // for a stream that only implements Seekable (not ByteBufferReadable).
    final byte[] source = RandomUtils.secure().randomBytes(SOURCE_SIZE);
    final SeekableOnlyInputStream underlying = new SeekableOnlyInputStream(source);
    try (OzoneFSInputStream subject = new OzoneFSInputStream(underlying,
        new FileSystem.Statistics("test"))) {
      PositionedReadTestHelper.runConcurrentPositionedReads(source, (offset, buf) -> {
        byte[] arr = new byte[buf.remaining()];
        subject.readFully(offset, arr);
        buf.put(arr);
      });
    }
  }

  @Test
  @Timeout(value = 30)
  public void testConcurrentPositionedRead() throws Exception {
    final byte[] source = RandomUtils.secure().randomBytes(SOURCE_SIZE);
    final InterleavingSeekableInputStream underlying =
        new InterleavingSeekableInputStream(source);
    try (OzoneFSInputStream subject = new OzoneFSInputStream(underlying,
        new FileSystem.Statistics("test"))) {
      PositionedReadTestHelper.runConcurrentPositionedReads(source,
          (offset, buf) -> subject.readFully(offset, buf));
    }
  }

  @Test
  @Timeout(value = 30)
  public void testConcurrentPositionedReadEcFallback() throws Exception {
    final byte[] source = RandomUtils.secure().randomBytes(SOURCE_SIZE);
    final EcInterleavingInputStream underlying =
        new EcInterleavingInputStream(source);
    try (OzoneFSInputStream subject = new OzoneFSInputStream(underlying,
        new FileSystem.Statistics("test"))) {
      PositionedReadTestHelper.runConcurrentPositionedReads(source,
          (offset, buf) -> subject.readFully(offset, buf));
    }
  }

  @Test
  @Timeout(value = 30)
  public void testConcurrentByteArrayPositionedReadEcFallback() throws Exception {
    final byte[] source = RandomUtils.secure().randomBytes(SOURCE_SIZE);
    final EcInterleavingInputStream underlying =
        new EcInterleavingInputStream(source);
    try (OzoneFSInputStream subject = new OzoneFSInputStream(underlying,
        new FileSystem.Statistics("test"))) {
      PositionedReadTestHelper.runConcurrentPositionedReads(source, (offset, buf) -> {
        byte[] arr = new byte[buf.remaining()];
        subject.readFully(offset, arr);
        buf.put(arr);
      });
    }
  }

  @Test
  @Timeout(value = 30)
  public void testConcurrentMixedApiEcFallback() throws Exception {
    // ByteBuffer and byte-array callers share positionedReadLock; verify no interleaving.
    final byte[] source = RandomUtils.secure().randomBytes(SOURCE_SIZE);
    final EcInterleavingInputStream underlying =
        new EcInterleavingInputStream(source);
    try (OzoneFSInputStream subject = new OzoneFSInputStream(underlying,
        new FileSystem.Statistics("test"))) {
      PositionedReadTestHelper.runConcurrentPositionedReads(source, (offset, buf) -> {
        if ((offset & 1) == 0) {
          subject.readFully(offset, buf);
        } else {
          byte[] arr = new byte[buf.remaining()];
          subject.readFully(offset, arr);
          buf.put(arr);
        }
      });
    }
  }

  @Test
  void positionedReadDelegatesThroughWrappersAndCountsBytesOnce() throws Exception {
    byte[] source = RandomUtils.secure().randomBytes(32);
    for (boolean nativeRead : new boolean[] {false, true}) {
      FileSystem.Statistics statistics = new FileSystem.Statistics("test");
      InputStream input = nativeRead ? new OzoneInputStream(new NativePositionedInputStream(source))
          : new SeekableOnlyInputStream(source);
      try (OzoneFSInputStream stream = new OzoneFSInputStream(input, statistics)) {
        byte[] result = new byte[12];
        stream.readFully(3, result, 2, 7);
        assertArrayEquals(Arrays.copyOfRange(source, 3, 10), Arrays.copyOfRange(result, 2, 9));
        assertEquals(7, statistics.getBytesRead());
        ByteBuffer destination = ByteBuffer.wrap(result, 2, 7).slice();
        assertEquals(3, stream.read(29, destination));
        assertArrayEquals(Arrays.copyOfRange(source, 29, 32), Arrays.copyOfRange(result, 2, 5));
        assertEquals(10, statistics.getBytesRead());
        assertEquals(-1, stream.read(32, ByteBuffer.allocateDirect(1)));
        assertThrows(EOFException.class, () -> stream.readFully(31, ByteBuffer.allocateDirect(2)));
        assertEquals(11, statistics.getBytesRead());
      }
    }
  }

  private static final class NativePositionedInputStream extends ByteArrayInputStream
      implements ByteBufferPositionedReadable {
    private NativePositionedInputStream(byte[] data) {
      super(data);
    }

    @Override
    public int read(long position, ByteBuffer destination) {
      if (position >= count) {
        return -1;
      }
      int n = Math.min(destination.remaining(), count - (int) position);
      destination.put(buf, (int) position, n);
      return n;
    }

    @Override
    public void readFully(long position, ByteBuffer destination) throws IOException {
      int length = destination.remaining();
      if (read(position, destination) < length) {
        throw new EOFException();
      }
    }
  }

  /**
   * Mimics KeyInputStream synchronized per-operation seek/read where multi-steps
   * positioned reads must still be serialized at the FS layer.
   */
  private static final class InterleavingSeekableInputStream extends InputStream
      implements Seekable, org.apache.hadoop.fs.ByteBufferReadable {

    private final InterleavingReadState readState;

    private InterleavingSeekableInputStream(byte[] data) {
      this.readState = new InterleavingReadState(data);
    }

    @Override
    public synchronized void seek(long p) {
      readState.seek(p);
    }

    @Override
    public synchronized long getPos() {
      return readState.getPos();
    }

    @Override
    public synchronized boolean seekToNewSource(long targetPos) {
      return false;
    }

    @Override
    public int read() {
      return -1;
    }

    @Override
    public synchronized int read(ByteBuffer buf) {
      return readState.read(buf);
    }
  }

  /**
   * Mimics an erasure-coded key stream using ExtendedInputStream's synchronized positioned-read fallback.
   */
  private static final class EcInterleavingInputStream extends ExtendedInputStream {

    private final InterleavingReadState readState;

    private EcInterleavingInputStream(byte[] data) {
      this.readState = new InterleavingReadState(data);
    }

    @Override
    protected int readWithStrategy(ByteReaderStrategy strategy) {
      throw new UnsupportedOperationException();
    }

    @Override
    public synchronized void seek(long p) {
      readState.seek(p);
    }

    @Override
    public synchronized long getPos() {
      return readState.getPos();
    }

    @Override
    public synchronized boolean seekToNewSource(long targetPos) {
      return false;
    }

    @Override
    public synchronized int read(ByteBuffer buf) {
      return readState.read(buf);
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      return readState.read(b, off, len);
    }

    @Override
    public void unbuffer() {
      return;
    }
  }

  /**
   * A Seekable stream that does NOT implement ByteBufferReadable. Used to verify
   * that the byte-array positioned-read fallback uses read(byte[]) rather than
   * casting to ByteBufferReadable (which would throw ClassCastException).
   */
  private static final class SeekableOnlyInputStream extends InputStream
      implements Seekable {

    private final byte[] data;
    private int pos;

    private SeekableOnlyInputStream(byte[] data) {
      this.data = data;
    }

    @Override
    public synchronized int read() {
      return pos < data.length ? (data[pos++] & 0xFF) : -1;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      if (pos >= data.length) {
        return -1;
      }
      int n = Math.min(len, data.length - pos);
      System.arraycopy(data, pos, b, off, n);
      pos += n;
      return n;
    }

    @Override
    public synchronized int available() {
      return data.length - pos;
    }

    @Override
    public synchronized void seek(long newPos) {
      pos = (int) newPos;
    }

    @Override
    public synchronized long getPos() {
      return pos;
    }

    @Override
    public boolean seekToNewSource(long targetPos) {
      return false;
    }
  }

  private static final class InterleavingReadState {
    private final byte[] data;
    private long pos;
    private final ThreadLocal<Long> expectedReadPos = new ThreadLocal<>();

    private InterleavingReadState(byte[] data) {
      this.data = data;
    }

    private void seek(long p) {
      pos = p;
      expectedReadPos.set(p);
    }

    private long getPos() {
      return pos;
    }

    private int read(ByteBuffer buf) {
      Long expected = expectedReadPos.get();
      if (expected != null && pos != expected) {
        int len = buf.remaining();
        for (int i = 0; i < len; i++) {
          buf.put(CORRUPT_BYTE);
        }
        return len;
      }
      int toRead = Math.min(buf.remaining(), data.length - (int) pos);
      if (toRead <= 0) {
        return -1;
      }
      buf.put(data, (int) pos, toRead);
      pos += toRead;
      expectedReadPos.remove();
      return toRead;
    }

    private int read(byte[] b, int off, int len) {
      Long expected = expectedReadPos.get();
      if (expected != null && pos != expected) {
        java.util.Arrays.fill(b, off, off + len, CORRUPT_BYTE);
        return len;
      }
      int toRead = Math.min(len, data.length - (int) pos);
      if (toRead <= 0) {
        return -1;
      }
      System.arraycopy(data, (int) pos, b, off, toRead);
      pos += toRead;
      expectedReadPos.remove();
      return toRead;
    }
  }

}
