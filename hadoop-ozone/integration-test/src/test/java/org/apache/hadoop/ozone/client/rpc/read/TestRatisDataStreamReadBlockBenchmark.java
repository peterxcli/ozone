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

package org.apache.hadoop.ozone.client.rpc.read;

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor.ONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.apache.hadoop.hdds.StringUtils;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.OzoneClientConfig;
import org.apache.hadoop.hdds.scm.storage.RatisDataStreamBlockInputStream;
import org.apache.hadoop.hdds.scm.storage.StreamBlockInputStream;
import org.apache.hadoop.hdds.utils.db.CodecBuffer;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.io.KeyInputStream;
import org.apache.hadoop.ozone.client.protocol.ClientProtocol;
import org.apache.hadoop.ozone.om.TestBucket;
import org.apache.ozone.test.GenericTestUtils;
import org.apache.ratis.util.SizeInBytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Manual benchmark comparing Ozone's streaming ReadBlock path with
 * ReadBlock-over-Ratis-data-stream.
 */
public class TestRatisDataStreamReadBlockBenchmark {
  private static final String ENABLE_PROPERTY =
      "ozone.ratis.datastream.benchmark";
  private static final String KEY_SIZE_PROPERTY =
      "ozone.ratis.datastream.benchmark.key.size";
  private static final String BUFFER_SIZE_PROPERTY =
      "ozone.ratis.datastream.benchmark.buffer.size";
  private static final String KEY_SIZES_PROPERTY =
      "ozone.ratis.datastream.benchmark.key.sizes";
  private static final String BUFFER_SIZES_PROPERTY =
      "ozone.ratis.datastream.benchmark.buffer.sizes";
  private static final String WARMUP_PROPERTY =
      "ozone.ratis.datastream.benchmark.warmup";
  private static final String RUNS_PROPERTY =
      "ozone.ratis.datastream.benchmark.runs";
  private static final String RANDOM_BUFFER_SIZES_PROPERTY =
      "ozone.ratis.datastream.benchmark.random.buffer.sizes";
  private static final String RANDOM_READS_PROPERTY =
      "ozone.ratis.datastream.benchmark.random.reads";
  private static final String RANDOM_SEED_PROPERTY =
      "ozone.ratis.datastream.benchmark.random.seed";

  private static final int BYTES_PER_CHECKSUM = 16 << 10;
  private static final SizeInBytes BYTES_PER_CHECKSUM_SIZE =
      SizeInBytes.valueOf("16k");
  private static final SizeInBytes CREATE_BUFFER_SIZE =
      SizeInBytes.valueOf("8M");

  {
    GenericTestUtils.setLogLevel(LoggerFactory.getLogger("com"), Level.ERROR);
    GenericTestUtils.setLogLevel(LoggerFactory.getLogger("org"), Level.ERROR);
    GenericTestUtils.setLogLevel(
        LoggerFactory.getLogger("BackgroundPipelineScrubber"), Level.ERROR);
    GenericTestUtils.setLogLevel(
        LoggerFactory.getLogger("ExpiredContainerReplicaOpScrubber"),
        Level.ERROR);
    GenericTestUtils.setLogLevel(
        LoggerFactory.getLogger("SCMHATransactionMonitor"), Level.ERROR);
    GenericTestUtils.setLogLevel(LoggerFactory.getLogger(CodecBuffer.class),
        Level.ERROR);
  }

  @Test
  @EnabledIfSystemProperty(named = ENABLE_PROPERTY, matches = "true")
  void compareStreamReadAndRatisDataStreamRead() throws Exception {
    final SizeInBytes keySize = sizeProperty(KEY_SIZE_PROPERTY, "256M");
    final SizeInBytes bufferSize = sizeProperty(BUFFER_SIZE_PROPERTY, "8M");
    final int warmups = intProperty(WARMUP_PROPERTY, 1);
    final int runs = intProperty(RUNS_PROPERTY, 5);

    System.out.printf("%nReadBlock benchmark: key=%s, buffer=%s, "
        + "bytesPerChecksum=%d, warmup=%d, runs=%d%n",
        keySize, bufferSize, BYTES_PER_CHECKSUM, warmups, runs);

    try (MiniOzoneCluster cluster =
             TestStreamRead.newCluster(BYTES_PER_CHECKSUM)) {
      cluster.waitForClusterToBeReady();

      final OzoneConfiguration streamReadConf =
          readConf(cluster.getConf(), true, false);
      final OzoneConfiguration ratisStreamReadConf =
          readConf(cluster.getConf(), false, true);

      try (OzoneClient streamReadClient =
               OzoneClientFactory.getRpcClient(streamReadConf);
           OzoneClient ratisStreamReadClient =
               OzoneClientFactory.getRpcClient(ratisStreamReadConf)) {
        final TestBucket testBucket =
            TestBucket.newBuilder(streamReadClient).build();
        final String volume = testBucket.delegate().getVolumeName();
        final String bucket = testBucket.delegate().getName();
        final String keyName = "ratis-datastream-read-benchmark";
        final String expectedMd5 =
            createKey(testBucket.delegate(), keyName, keySize);

        verifyRead("readblock-stream-read", streamReadClient, volume, bucket,
            keyName, keySize, bufferSize, expectedMd5,
            StreamBlockInputStream.class);
        verifyRead("ratis-datastream-read", ratisStreamReadClient, volume,
            bucket, keyName, keySize, bufferSize, expectedMd5,
            RatisDataStreamBlockInputStream.class);

        final double[] readBlockStream = new double[runs];
        final double[] ratis = new double[runs];
        for (int i = 0; i < warmups; i++) {
          measurePair(i, true, streamReadClient, ratisStreamReadClient, volume,
              bucket, keyName, keySize, bufferSize, null, null);
        }
        for (int i = 0; i < runs; i++) {
          measurePair(i, false, streamReadClient, ratisStreamReadClient, volume,
              bucket, keyName, keySize, bufferSize, readBlockStream, ratis);
        }

        printSummary("readblock-stream-read", readBlockStream);
        printSummary("ratis-datastream-read", ratis);
      }
    }
  }

  @Test
  @EnabledIfSystemProperty(named = ENABLE_PROPERTY, matches = "true")
  void comparePr6613StyleReadTrend() throws Exception {
    final List<SizeInBytes> keySizes =
        sizeListProperty(KEY_SIZES_PROPERTY, "256M,500M,1G");
    final List<SizeInBytes> bufferSizes =
        sizeListProperty(BUFFER_SIZES_PROPERTY, "32M,8M,1M,4k");
    final List<SizeInBytes> randomBufferSizes =
        sizeListProperty(RANDOM_BUFFER_SIZES_PROPERTY, "1M,4k");
    final int randomReads = intProperty(RANDOM_READS_PROPERTY, 32);
    final long randomSeed = longProperty(RANDOM_SEED_PROPERTY, 0x52425231L);

    System.out.printf("%nPR-6613-style ReadBlock trend benchmark: "
            + "keySizes=%s, sequentialBuffers=%s, randomBuffers=%s, "
            + "randomReads=%d, randomSeed=%d, bytesPerChecksum=%s%n",
        keySizes, bufferSizes, randomBufferSizes, randomReads, randomSeed,
        BYTES_PER_CHECKSUM_SIZE);

    try (MiniOzoneCluster cluster =
             TestStreamRead.newCluster(BYTES_PER_CHECKSUM)) {
      cluster.waitForClusterToBeReady();

      final OzoneConfiguration streamReadConf =
          readConf(cluster.getConf(), true, false);
      final OzoneConfiguration ratisStreamReadConf =
          readConf(cluster.getConf(), false, true);

      try (OzoneClient streamReadClient =
               OzoneClientFactory.getRpcClient(streamReadConf);
           OzoneClient ratisStreamReadClient =
               OzoneClientFactory.getRpcClient(ratisStreamReadConf)) {
        final TestBucket testBucket =
            TestBucket.newBuilder(streamReadClient).build();
        final String volume = testBucket.delegate().getVolumeName();
        final String bucket = testBucket.delegate().getName();

        for (SizeInBytes keySize : keySizes) {
          final String keyName = "ratis-datastream-read-trend-"
              + keySize.getSize();

          System.out.println("---------------------------------------------------------");
          final String expectedMd5 =
              createKey(testBucket.delegate(), keyName, keySize);

          System.out.println("---------------------------------------------------------");
          System.out.printf("%s with %s bytes and %s bytesPerChecksum%n",
              keyName, keySize, BYTES_PER_CHECKSUM_SIZE);

          for (SizeInBytes bufferSize : bufferSizes) {
            final ReadResult readBlockStreamNoMd5 =
                readStreamKey("readblock-stream-readKey", streamReadClient,
                    volume, bucket, keyName, keySize, bufferSize, null,
                    StreamBlockInputStream.class);
            final ReadResult readBlockStreamMd5 =
                readStreamKey("readblock-stream-readKey", streamReadClient,
                    volume, bucket, keyName, keySize, bufferSize, expectedMd5,
                    StreamBlockInputStream.class);
            final ReadResult ratisNoMd5 =
                readStreamKey("ratis-readStreamKey", ratisStreamReadClient,
                    volume, bucket, keyName, keySize, bufferSize, null,
                    RatisDataStreamBlockInputStream.class);
            final ReadResult ratisMd5 =
                readStreamKey("ratis-readStreamKey", ratisStreamReadClient,
                    volume, bucket, keyName, keySize, bufferSize, expectedMd5,
                    RatisDataStreamBlockInputStream.class);
            printBandwidthImprovement("sequential-no-md5-improvement",
                keySize, bufferSize, readBlockStreamNoMd5, ratisNoMd5);
            printBandwidthImprovement("sequential-md5-improvement",
                keySize, bufferSize, readBlockStreamMd5, ratisMd5);
          }

          if (randomReads > 0) {
            System.out.println("---------------------------------------------------------");
            System.out.printf("%s random read workload with %d reads%n",
                keyName, randomReads);
            for (SizeInBytes bufferSize : randomBufferSizes) {
              final int readSize = Math.toIntExact(
                  Math.min(bufferSize.getSize(), keySize.getSize()));
              final long[] offsets = randomOffsets(keySize.getSize(), readSize,
                  randomReads, randomSeed ^ keySize.getSize()
                      ^ bufferSize.getSize());
              final ReadResult readBlockStreamRandom =
                  randomReadStreamKey("readblock-randomReadKey",
                      streamReadClient, volume, bucket, keyName, keySize,
                      bufferSize, readSize, offsets,
                      StreamBlockInputStream.class);
              final ReadResult ratisRandom =
                  randomReadStreamKey("ratis-randomReadKey",
                      ratisStreamReadClient, volume, bucket, keyName, keySize,
                      bufferSize, readSize, offsets,
                      RatisDataStreamBlockInputStream.class);
              printRandomImprovement("random-read-improvement", keySize,
                  bufferSize, readSize, offsets.length, readBlockStreamRandom,
                  ratisRandom);
            }
          }
        }
      }
    }
  }

  private static OzoneConfiguration readConf(OzoneConfiguration base,
      boolean streamReadBlock, boolean ratisStreamReadBlock) {
    final OzoneConfiguration conf = new OzoneConfiguration(base);
    final OzoneClientConfig clientConfig =
        conf.getObject(OzoneClientConfig.class);
    clientConfig.setStreamReadBlock(streamReadBlock);
    clientConfig.setRatisStreamReadBlock(ratisStreamReadBlock);
    conf.setFromObject(clientConfig);
    return conf;
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static void verifyRead(String name, OzoneClient client, String volume,
      String bucket, String keyName, SizeInBytes keySize,
      SizeInBytes bufferSize, String expectedMd5, Class<?> expectedStreamClass)
      throws Exception {
    try (KeyInputStream in = getKeyInputStream(client, volume, bucket,
        keyName)) {
      assertStreamClass(name, in, expectedStreamClass);
      final String computedMd5 = readMd5(keySize, bufferSize, in);
      assertEquals(expectedMd5, computedMd5, name);
      System.out.printf("%24s verified md5=%s%n", name, computedMd5);
    }
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static void measurePair(int index, boolean warmup,
      OzoneClient streamReadClient, OzoneClient ratisStreamReadClient,
      String volume, String bucket, String keyName, SizeInBytes keySize,
      SizeInBytes bufferSize, double[] readBlockStreamResults,
      double[] ratisResults)
      throws Exception {
    if (index % 2 == 0) {
      measureAndStore("readblock-stream-read", warmup, index,
          streamReadClient, volume, bucket, keyName, keySize, bufferSize,
          StreamBlockInputStream.class, readBlockStreamResults);
      measureAndStore("ratis-datastream-read", warmup, index,
          ratisStreamReadClient, volume, bucket, keyName, keySize, bufferSize,
          RatisDataStreamBlockInputStream.class, ratisResults);
    } else {
      measureAndStore("ratis-datastream-read", warmup, index,
          ratisStreamReadClient, volume, bucket, keyName, keySize, bufferSize,
          RatisDataStreamBlockInputStream.class, ratisResults);
      measureAndStore("readblock-stream-read", warmup, index,
          streamReadClient, volume, bucket, keyName, keySize, bufferSize,
          StreamBlockInputStream.class, readBlockStreamResults);
    }
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static void measureAndStore(String name, boolean warmup, int index,
      OzoneClient client, String volume, String bucket, String keyName,
      SizeInBytes keySize, SizeInBytes bufferSize, Class<?> expectedStreamClass,
      double[] results) throws Exception {
    try (KeyInputStream in = getKeyInputStream(client, volume, bucket,
        keyName)) {
      assertStreamClass(name, in, expectedStreamClass);
      final double mbps = measureRead(name, warmup, index, keySize, bufferSize,
          in);
      if (results != null) {
        results[index] = mbps;
      }
    }
  }

  private static KeyInputStream getKeyInputStream(OzoneClient client,
      String volume, String bucket, String keyName) throws Exception {
    final ClientProtocol proxy = client.getProxy();
    return (KeyInputStream) proxy.getKey(volume, bucket, keyName)
        .getInputStream();
  }

  private static void assertStreamClass(String name, KeyInputStream in,
      Class<?> expectedStreamClass) {
    assertTrue(in.isStreamBlockInputStream(), name);
    assertTrue(in.getPartStreams().stream()
            .allMatch(expectedStreamClass::isInstance),
        () -> name + " used " + in.getPartStreams());
  }

  private static double measureRead(String name, boolean warmup, int index,
      SizeInBytes keySize, SizeInBytes bufferSize, InputStream in)
      throws Exception {
    final ReadResult result = readKey(name, keySize, bufferSize, false, in);
    final String phase = warmup ? "warmup" : "run";
    System.out.printf("%24s %-6s %2d: %8.2f MB/s (%7.3f s)%n",
        name, phase, index + 1, result.mbps(),
        result.elapsedSeconds());
    return result.mbps();
  }

  private static String createKey(OzoneBucket bucket, String keyName,
      SizeInBytes keySize) throws Exception {
    return createKey(bucket, keyName, keySize, CREATE_BUFFER_SIZE);
  }

  private static String createKey(OzoneBucket bucket, String keyName,
      SizeInBytes keySize, SizeInBytes bufferSize) throws Exception {
    final MessageDigest md5 = MessageDigest.getInstance("MD5");
    final byte[] buffer = new byte[bufferSize.getSizeInt()];
    for (int i = 0; i < buffer.length; i++) {
      buffer[i] = (byte) ((i * 31) & 0xff);
    }

    final long keySizeBytes = keySize.getSize();
    final long startTime = System.nanoTime();
    try (OutputStream out = bucket.createStreamKey(keyName, keySizeBytes,
        RatisReplicationConfig.getInstance(ONE), Collections.emptyMap())) {
      for (long pos = 0; pos < keySizeBytes;) {
        final int writeSize =
            Math.toIntExact(Math.min(buffer.length, keySizeBytes - pos));
        out.write(buffer, 0, writeSize);
        md5.update(buffer, 0, writeSize);
        pos += writeSize;
      }
    }
    final long elapsedNanos = System.nanoTime() - startTime;
    final String computedMd5 = StringUtils.bytes2Hex(md5.digest());
    System.out.printf("%24s: %8.2f MB/s (%7.3f s, buffer %s, "
            + "keySize %8.2f MB, md5=%s)%n",
        "createStreamKey", mbps(keySizeBytes, elapsedNanos),
        elapsedNanos / 1_000_000_000.0, bufferSize,
        keySizeBytes * 1.0 / (1 << 20), computedMd5);
    return computedMd5;
  }

  private static String readMd5(SizeInBytes keySize, SizeInBytes bufferSize,
      InputStream in) throws Exception {
    return readKey("readMd5", keySize, bufferSize, true, in).md5();
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static ReadResult readStreamKey(String name, OzoneClient client,
      String volume, String bucket, String keyName, SizeInBytes keySize,
      SizeInBytes bufferSize, String expectedMd5, Class<?> expectedStreamClass)
      throws Exception {
    try (KeyInputStream in = getKeyInputStream(client, volume, bucket,
        keyName)) {
      assertStreamClass(name, in, expectedStreamClass);
      final ReadResult result =
          readKey(name, keySize, bufferSize, expectedMd5 != null, in);
      assertEquals(expectedMd5, result.md5(), name);
      System.out.printf("%24s: %8.2f MB/s (%7.3f s, buffer %s, "
              + "keySize %8.2f MB, md5=%s)%n",
          name, result.mbps(), result.elapsedSeconds(), bufferSize,
          keySize.getSize() * 1.0 / (1 << 20), result.md5());
      return result;
    }
  }

  private static ReadResult readKey(String name, SizeInBytes keySize,
      SizeInBytes bufferSize, boolean computeMd5, InputStream in)
      throws Exception {
    final MessageDigest md5 = computeMd5
        ? MessageDigest.getInstance("MD5") : null;
    final byte[] buffer = new byte[bufferSize.getSizeInt()];
    final long keySizeBytes = keySize.getSize();
    final long startTime = System.nanoTime();
    long pos = 0;
    while (pos < keySizeBytes) {
      final int read = in.read(buffer, 0, buffer.length);
      if (read < 0) {
        break;
      }
      if (computeMd5) {
        md5.update(buffer, 0, read);
      }
      pos += read;
    }
    assertEquals(keySizeBytes, pos, name);

    final long elapsedNanos = System.nanoTime() - startTime;
    return new ReadResult(elapsedNanos, mbps(keySizeBytes, elapsedNanos),
        computeMd5 ? StringUtils.bytes2Hex(md5.digest()) : null);
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static ReadResult randomReadStreamKey(String name, OzoneClient client,
      String volume, String bucket, String keyName, SizeInBytes keySize,
      SizeInBytes bufferSize, int readSize, long[] offsets,
      Class<?> expectedStreamClass) throws Exception {
    try (KeyInputStream in = getKeyInputStream(client, volume, bucket,
        keyName)) {
      assertStreamClass(name, in, expectedStreamClass);
      final ReadResult result = readRandomKey(name, in, readSize, offsets);
      System.out.printf("%24s: %8.2f MB/s (%7.3f s, buffer %s, "
              + "keySize %8.2f MB, reads %d, total %8.2f MB)%n",
          name, result.mbps(), result.elapsedSeconds(), bufferSize,
          keySize.getSize() * 1.0 / (1 << 20), offsets.length,
          readSize * offsets.length * 1.0 / (1 << 20));
      return result;
    }
  }

  private static void printBandwidthImprovement(String name,
      SizeInBytes keySize, SizeInBytes bufferSize, ReadResult readBlockStream,
      ReadResult ratis) {
    System.out.printf("%24s: %6.2fx bandwidth (%8.2f -> %8.2f MB/s, "
            + "buffer %s, keySize %8.2f MB)%n",
        name, ratio(ratis.mbps(), readBlockStream.mbps()),
        readBlockStream.mbps(), ratis.mbps(), bufferSize,
        keySize.getSize() * 1.0 / (1 << 20));
  }

  private static void printRandomImprovement(String name, SizeInBytes keySize,
      SizeInBytes bufferSize, int readSize, int reads,
      ReadResult readBlockStream, ReadResult ratis) {
    final double readBlockStreamIops = iops(reads, readBlockStream);
    final double ratisIops = iops(reads, ratis);
    System.out.printf("%24s: %6.2fx bandwidth, %6.2fx IOPS "
            + "(%8.2f -> %8.2f MB/s, %8.2f -> %8.2f ops/s, read %s, "
            + "buffer %s, keySize %8.2f MB)%n",
        name, ratio(ratis.mbps(), readBlockStream.mbps()),
        ratio(ratisIops, readBlockStreamIops), readBlockStream.mbps(),
        ratis.mbps(), readBlockStreamIops, ratisIops,
        SizeInBytes.valueOf(readSize), bufferSize,
        keySize.getSize() * 1.0 / (1 << 20));
  }

  private static ReadResult readRandomKey(String name, KeyInputStream in,
      int readSize, long[] offsets) throws Exception {
    final byte[] buffer = new byte[readSize];
    final long startTime = System.nanoTime();
    long totalRead = 0;
    for (long offset : offsets) {
      in.seek(offset);
      int remaining = readSize;
      while (remaining > 0) {
        final int position = readSize - remaining;
        final int read = in.read(buffer, position, remaining);
        assertTrue(read > 0, name + " read returned " + read
            + " at offset " + offset + ", remaining " + remaining);
        remaining -= read;
        totalRead += read;
      }
    }
    final long elapsedNanos = System.nanoTime() - startTime;
    return new ReadResult(elapsedNanos, mbps(totalRead, elapsedNanos), null);
  }

  private static long[] randomOffsets(long keySize, int readSize, int reads,
      long seed) {
    final Random random = new Random(seed);
    final long[] offsets = new long[reads];
    final long maxStart = keySize - readSize;
    final long slots = maxStart / BYTES_PER_CHECKSUM + 1;
    for (int i = 0; i < offsets.length; i++) {
      offsets[i] = nextLong(random, slots) * BYTES_PER_CHECKSUM;
    }
    return offsets;
  }

  private static long nextLong(Random random, long bound) {
    if (bound <= 0) {
      return 0;
    }
    long bits;
    long value;
    do {
      bits = random.nextLong() >>> 1;
      value = bits % bound;
    } while (bits - value + (bound - 1) < 0L);
    return value;
  }

  private static void printSummary(String name, double[] values) {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double sum = 0;
    for (double value : values) {
      min = Math.min(min, value);
      max = Math.max(max, value);
      sum += value;
    }
    System.out.printf("%24s summary: avg %8.2f MB/s, min %8.2f, "
        + "max %8.2f, runs %d%n", name, sum / values.length, min, max,
        values.length);
  }

  private static double mbps(long bytes, long elapsedNanos) {
    return (bytes * 1.0 / (1 << 20)) / (elapsedNanos / 1_000_000_000.0);
  }

  private static double iops(int operations, ReadResult result) {
    return operations / result.elapsedSeconds();
  }

  private static double ratio(double numerator, double denominator) {
    return denominator == 0 ? Double.NaN : numerator / denominator;
  }

  private static SizeInBytes sizeProperty(String name, String defaultValue) {
    return SizeInBytes.valueOf(System.getProperty(name, defaultValue));
  }

  private static List<SizeInBytes> sizeListProperty(String name,
      String defaultValue) {
    return Arrays.stream(System.getProperty(name, defaultValue).split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(SizeInBytes::valueOf)
        .collect(java.util.stream.Collectors.toList());
  }

  private static int intProperty(String name, int defaultValue) {
    return Math.max(0, Integer.getInteger(name, defaultValue));
  }

  private static long longProperty(String name, long defaultValue) {
    return Long.getLong(name, defaultValue);
  }

  private static final class ReadResult {
    private final long elapsedNanos;
    private final double mbps;
    private final String md5;

    private ReadResult(long elapsedNanos, double mbps, String md5) {
      this.elapsedNanos = elapsedNanos;
      this.mbps = mbps;
      this.md5 = md5;
    }

    double elapsedSeconds() {
      return elapsedNanos / 1_000_000_000.0;
    }

    double mbps() {
      return mbps;
    }

    String md5() {
      return md5;
    }
  }
}
