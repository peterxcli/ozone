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

import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link LocalOzoneCluster}.
 */
class TestLocalOzoneCluster {

  @TempDir
  private Path tempDir;

  @Test
  void prepareConfigurationUsesRequestedPrimaryPorts() throws Exception {
    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(
            tempDir.resolve("local-ozone"))
        .setScmPort(9860)
        .setOmPort(9862)
        .build();

    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      LocalOzoneCluster.PreparedConfiguration prepared =
          cluster.prepareConfiguration();
      OzoneConfiguration conf = prepared.getConfiguration();

      assertTrue(conf.get(OZONE_METADATA_DIRS)
          .startsWith(tempDir.resolve("local-ozone").toString()));
      assertTrue(conf.get(OZONE_SCM_CLIENT_ADDRESS_KEY).endsWith(":9860"));
      assertTrue(conf.get(OZONE_OM_ADDRESS_KEY).endsWith(":9862"));
      assertEquals(9860, prepared.getScmPort());
      assertEquals(9862, prepared.getOmPort());
    }
  }

  @Test
  void prepareConfigurationClearsExistingStateForFormatAlways()
      throws Exception {
    Path dataDir = tempDir.resolve("local-ozone");
    Files.createDirectories(dataDir);
    Files.write(dataDir.resolve("marker.txt"),
        "stale".getBytes(StandardCharsets.UTF_8));

    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(dataDir)
        .setFormatMode(LocalOzoneClusterConfig.FormatMode.ALWAYS)
        .build();

    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      cluster.prepareConfiguration();
    }

    assertFalse(Files.exists(dataDir.resolve("marker.txt")));
    assertTrue(Files.isDirectory(dataDir.resolve("metadata")));
  }

  @Test
  void closeDeletesEphemeralDataDir() throws Exception {
    Path dataDir = tempDir.resolve("ephemeral-local-ozone");
    Files.createDirectories(dataDir);
    Files.write(dataDir.resolve("marker.txt"),
        "ephemeral".getBytes(StandardCharsets.UTF_8));

    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(dataDir)
        .setEphemeral(true)
        .build();

    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      cluster.close();
    }

    assertFalse(Files.exists(dataDir));
  }
}
