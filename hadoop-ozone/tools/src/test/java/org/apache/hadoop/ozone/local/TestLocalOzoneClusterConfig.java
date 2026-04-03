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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LocalOzoneClusterConfig}.
 */
class TestLocalOzoneClusterConfig {

  @Test
  void builderProvidesLocalSingleNodeDefaults() {
    Path dataDir = Paths.get("target", "local-ozone");

    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(dataDir)
        .build();

    assertEquals(dataDir.toAbsolutePath().normalize(), config.getDataDir());
    assertEquals(LocalOzoneClusterConfig.FormatMode.IF_NEEDED,
        config.getFormatMode());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_DATANODES,
        config.getDatanodes());
    assertFalse(config.isEphemeral());
    assertTrue(config.isS3gEnabled());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_HOST, config.getHost());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_BIND_HOST,
        config.getBindHost());
    assertEquals(0, config.getScmPort());
    assertEquals(0, config.getOmPort());
    assertEquals(0, config.getS3gPort());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_STARTUP_TIMEOUT,
        config.getStartupTimeout());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_S3_ACCESS_KEY,
        config.getS3AccessKey());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_S3_SECRET_KEY,
        config.getS3SecretKey());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_S3_REGION,
        config.getS3Region());
  }

  @Test
  void builderAcceptsExplicitOverrides() {
    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(
            Paths.get("target", "custom-local-ozone"))
        .setFormatMode(LocalOzoneClusterConfig.FormatMode.ALWAYS)
        .setDatanodes(3)
        .setEphemeral(true)
        .setS3gEnabled(false)
        .setHost("localhost")
        .setBindHost("127.0.0.1")
        .setScmPort(9860)
        .setOmPort(9862)
        .setS3gPort(9878)
        .setStartupTimeout(Duration.ofSeconds(45))
        .setS3AccessKey("dev")
        .setS3SecretKey("secret")
        .setS3Region("ap-south-1")
        .build();

    assertEquals(LocalOzoneClusterConfig.FormatMode.ALWAYS,
        config.getFormatMode());
    assertEquals(3, config.getDatanodes());
    assertTrue(config.isEphemeral());
    assertFalse(config.isS3gEnabled());
    assertEquals("localhost", config.getHost());
    assertEquals("127.0.0.1", config.getBindHost());
    assertEquals(9860, config.getScmPort());
    assertEquals(9862, config.getOmPort());
    assertEquals(9878, config.getS3gPort());
    assertEquals(Duration.ofSeconds(45), config.getStartupTimeout());
    assertEquals("dev", config.getS3AccessKey());
    assertEquals("secret", config.getS3SecretKey());
    assertEquals("ap-south-1", config.getS3Region());
  }
}
