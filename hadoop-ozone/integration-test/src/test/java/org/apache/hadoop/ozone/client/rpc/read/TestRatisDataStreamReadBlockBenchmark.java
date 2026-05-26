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
 * Manual benchmark comparing Ozone's direct stream read path with
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

        verifyRead("direct-stream-read", streamReadClient, volume, bucket,
            keyName, keySize, bufferSize, expectedMd5,
            StreamBlockInputStream.class);
        verifyRead("ratis-datastream-read", ratisStreamReadClient, volume,
            bucket, keyName, keySize, bufferSize, expectedMd5,
            RatisDataStreamBlockInputStream.class);

        final double[] direct = new double[runs];
        final double[] ratis = new double[runs];
        for (int i = 0; i < warmups; i++) {
          measurePair(i, true, streamReadClient, ratisStreamReadClient, volume,
              bucket, keyName, keySize, bufferSize, null, null);
        }
        for (int i = 0; i < runs; i++) {
          measurePair(i, false, streamReadClient, ratisStreamReadClient, volume,
              bucket, keyName, keySize, bufferSize, direct, ratis);
        }

        printSummary("direct-stream-read", direct);
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

    System.out.printf("%nPR-6613-style ReadBlock trend benchmark: "
            + "keySizes=%s, buffers=%s, bytesPerChecksum=%s%n",
        keySizes, bufferSizes, BYTES_PER_CHECKSUM_SIZE);

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
            readStreamKey("direct-readStreamKey", streamReadClient, volume,
                bucket, keyName, keySize, bufferSize, null,
                StreamBlockInputStream.class);
            readStreamKey("direct-readStreamKey", streamReadClient, volume,
                bucket, keyName, keySize, bufferSize, expectedMd5,
                StreamBlockInputStream.class);
            readStreamKey("ratis-readStreamKey", ratisStreamReadClient,
                volume, bucket, keyName, keySize, bufferSize, null,
                RatisDataStreamBlockInputStream.class);
            readStreamKey("ratis-readStreamKey", ratisStreamReadClient,
                volume, bucket, keyName, keySize, bufferSize, expectedMd5,
                RatisDataStreamBlockInputStream.class);
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
      SizeInBytes bufferSize, double[] directResults, double[] ratisResults)
      throws Exception {
    if (index % 2 == 0) {
      measureAndStore("direct-stream-read", warmup, index, streamReadClient,
          volume, bucket, keyName, keySize, bufferSize,
          StreamBlockInputStream.class, directResults);
      measureAndStore("ratis-datastream-read", warmup, index,
          ratisStreamReadClient, volume, bucket, keyName, keySize, bufferSize,
          RatisDataStreamBlockInputStream.class, ratisResults);
    } else {
      measureAndStore("ratis-datastream-read", warmup, index,
          ratisStreamReadClient, volume, bucket, keyName, keySize, bufferSize,
          RatisDataStreamBlockInputStream.class, ratisResults);
      measureAndStore("direct-stream-read", warmup, index, streamReadClient,
          volume, bucket, keyName, keySize, bufferSize,
          StreamBlockInputStream.class, directResults);
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
  private static void readStreamKey(String name, OzoneClient client,
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
