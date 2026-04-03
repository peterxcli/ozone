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

package org.apache.hadoop.ozone.local;

import static org.apache.commons.io.IOUtils.toByteArray;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link LocalOzoneCluster}.
 */
class TestLocalOzoneClusterRuntime {

  @TempDir
  private Path tempDir;

  @Test
  void clusterStartsBecomesUsableAndRestartsWithExistingMetadata()
      throws Exception {
    String volumeName = "vol" + UUID.randomUUID().toString().replace("-", "");
    String bucketName = "bucket" + UUID.randomUUID().toString().replace("-", "");
    String keyName = "key" + UUID.randomUUID().toString().replace("-", "");
    byte[] payload = "local-ozone".getBytes(StandardCharsets.UTF_8);

    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(
            tempDir.resolve("local-ozone-runtime"))
        .setDatanodes(2)
        .setS3gEnabled(false)
        .setStartupTimeout(Duration.ofMinutes(2))
        .build();

    startClusterAndWriteKey(config, volumeName, bucketName, keyName, payload);
    restartClusterAndVerifyKey(config, volumeName, bucketName, keyName, payload);
  }

  private void startClusterAndWriteKey(LocalOzoneClusterConfig config,
      String volumeName, String bucketName, String keyName, byte[] payload)
      throws Exception {
    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      OzoneConfiguration clientConf =
          cluster.prepareConfiguration().getConfiguration();
      cluster.start();

      assertTrue(cluster.getScmPort() > 0);
      assertTrue(cluster.getOmPort() > 0);
      assertEquals(2, cluster.getStartedDatanodeCount());

      try (OzoneClient client = OzoneClientFactory.getRpcClient(clientConf)) {
        OzoneVolume volume = createVolume(client, volumeName);
        OzoneBucket bucket = createBucket(volume, bucketName);
        writeKey(bucket, keyName, payload);
      }
    }
  }

  private void restartClusterAndVerifyKey(LocalOzoneClusterConfig config,
      String volumeName, String bucketName, String keyName, byte[] payload)
      throws Exception {
    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      OzoneConfiguration clientConf =
          cluster.prepareConfiguration().getConfiguration();
      cluster.start();

      try (OzoneClient client = OzoneClientFactory.getRpcClient(clientConf)) {
        OzoneVolume volume = client.getObjectStore().getVolume(volumeName);
        OzoneBucket bucket = volume.getBucket(bucketName);
        try (OzoneInputStream input = bucket.readKey(keyName)) {
          assertArrayEquals(payload, toByteArray(input));
        }
      }
    }
  }

  private static OzoneVolume createVolume(OzoneClient client, String volumeName)
      throws Exception {
    client.getObjectStore().createVolume(volumeName);
    return client.getObjectStore().getVolume(volumeName);
  }

  private static OzoneBucket createBucket(OzoneVolume volume, String bucketName)
      throws Exception {
    volume.createBucket(bucketName);
    return volume.getBucket(bucketName);
  }

  private static void writeKey(OzoneBucket bucket, String keyName,
      byte[] payload) throws Exception {
    try (OzoneOutputStream output = bucket.createKey(keyName, payload.length,
        ReplicationType.STAND_ALONE, ReplicationFactor.ONE, null)) {
      output.write(payload);
    }
  }
}
