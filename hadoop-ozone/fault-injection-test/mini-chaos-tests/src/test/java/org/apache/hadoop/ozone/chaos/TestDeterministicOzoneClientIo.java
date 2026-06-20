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

package org.apache.hadoop.ozone.chaos;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.client.ReplicationFactor.ONE;
import static org.apache.hadoop.hdds.client.ReplicationType.RATIS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for deterministic fault injection around real Ozone client IO.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDeterministicOzoneClientIo {
  private static final String VOLUME = "deterministicozoneclientio";
  private static final String BUCKET = "bucket";

  private MiniOzoneCluster cluster;
  private OzoneClient client;
  private OzoneBucket bucket;
  private ReplicationConfig replication;

  @TempDir
  private Path tempDir;

  @BeforeAll
  void init() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    cluster = MiniOzoneCluster.newBuilder(conf).setNumDatanodes(1).build();
    cluster.waitForClusterToBeReady();

    client = cluster.newClient();
    client.getObjectStore().createVolume(VOLUME);
    client.getObjectStore().getVolume(VOLUME).createBucket(BUCKET);
    bucket = client.getObjectStore().getVolume(VOLUME).getBucket(BUCKET);
    replication = ReplicationConfig.fromTypeAndFactor(RATIS, ONE);
  }

  @AfterAll
  void shutdown() throws Exception {
    if (client != null) {
      client.close();
    }
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  void recoverableCreateFailureIsRetriedAndReplayable() throws Exception {
    String key = "recoverable-key";
    byte[] data = "recoverable".getBytes(UTF_8);
    Path trace = tempDir.resolve("recoverable-create.jsonl");
    DeterministicOzoneClientIo.FaultEvent fault =
        DeterministicOzoneClientIo.FaultEvent.of(0, 1,
            DeterministicOzoneClientIo.IoPoint.CREATE_KEY,
            DeterministicOzoneClientIo.FaultPhase.BEFORE,
            DeterministicOzoneClientIo.FaultAction.THROW_RECOVERABLE,
            DeterministicOzoneClientIo.FailureClass.RECOVERABLE);

    DeterministicOzoneClientIo.RunResult result =
        DeterministicOzoneClientIo.recording(bucket,
            new DeterministicOzoneClientIo.ScriptedFaultPolicy(Collections.singletonList(fault)), trace)
            .writeKeyWithRetry(key, data, replication);

    assertTrue(result.isSuccessful());
    assertEquals(2, result.attempts());
    assertEquals(Collections.singletonList(fault), result.faults());
    assertArrayEquals(data, readKey(key, data.length));
    assertTrue(Files.exists(trace));
    String recordedTrace = readTrace(trace);

    OzoneBucket replayBucket = createBucket("recoverable-replay");
    DeterministicOzoneClientIo.RunResult replay =
        DeterministicOzoneClientIo.replay(replayBucket, trace);

    assertTrue(replay.isSuccessful());
    assertEquals(result.attempts(), replay.attempts());
    assertArrayEquals(data, readKey(replayBucket, key, data.length));
    assertEquals(recordedTrace, readTrace(trace));
  }

  @Test
  void ambiguousCloseRetryIsDetectedAndReplayable() throws Exception {
    String key = "ambiguous-close-key";
    byte[] data = "ambiguous".getBytes(UTF_8);
    Path trace = tempDir.resolve("ambiguous-close.jsonl");
    DeterministicOzoneClientIo.FaultEvent fault =
        DeterministicOzoneClientIo.FaultEvent.of(0, 1,
            DeterministicOzoneClientIo.IoPoint.CLOSE_KEY,
            DeterministicOzoneClientIo.FaultPhase.AFTER_SUCCESS,
            DeterministicOzoneClientIo.FaultAction.DROP_RESPONSE_AFTER_SUCCESS,
            DeterministicOzoneClientIo.FailureClass.AMBIGUOUS_COMMIT);

    DeterministicOzoneClientIo.RunResult result =
        DeterministicOzoneClientIo.recording(bucket,
            new DeterministicOzoneClientIo.ScriptedFaultPolicy(Collections.singletonList(fault)), trace)
            .writeKeyWithRetry(key, data, replication);

    assertFalse(result.isSuccessful());
    assertEquals(DeterministicOzoneClientIo.FailureClass.AMBIGUOUS_COMMIT,
        result.failureClass());
    assertTrue(result.failureMessage().contains("Retried ambiguous close"));
    assertArrayEquals(data, readKey(key, data.length));
    String recordedTrace = readTrace(trace);

    OmKeyInfo keyInfo = cluster.getOzoneManager().lookupKey(toKeyArgs(key));
    assertEquals(data.length, keyInfo.getDataSize());

    OzoneBucket replayBucket = createBucket("ambiguous-replay");
    DeterministicOzoneClientIo.RunResult replay =
        DeterministicOzoneClientIo.replay(replayBucket, trace);

    assertFalse(replay.isSuccessful());
    assertEquals(result.failureClass(), replay.failureClass());
    assertEquals(result.failureMessage(), replay.failureMessage());
    assertArrayEquals(data, readKey(replayBucket, key, data.length));
    assertEquals(recordedTrace, readTrace(trace));
  }

  private OzoneBucket createBucket(String name) throws Exception {
    client.getObjectStore().getVolume(VOLUME).createBucket(name);
    return client.getObjectStore().getVolume(VOLUME).getBucket(name);
  }

  private byte[] readKey(String key, int length) throws Exception {
    return readKey(bucket, key, length);
  }

  private byte[] readKey(OzoneBucket targetBucket, String key, int length)
      throws Exception {
    try (OzoneInputStream in = targetBucket.readKey(key)) {
      return IOUtils.readFully(in, length);
    }
  }

  private String readTrace(Path trace) throws Exception {
    return new String(Files.readAllBytes(trace), UTF_8);
  }

  private OmKeyArgs toKeyArgs(String key) {
    return new OmKeyArgs.Builder()
        .setVolumeName(VOLUME)
        .setBucketName(BUCKET)
        .setReplicationConfig(replication)
        .setKeyName(key)
        .build();
  }
}
