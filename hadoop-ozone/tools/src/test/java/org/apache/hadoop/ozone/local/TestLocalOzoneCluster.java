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

import static org.apache.hadoop.hdds.recon.ReconConfigKeys.OZONE_RECON_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_DB_DIR;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTP_ADDRESS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        .setS3gPort(9878)
        .setReconEnabled(true)
        .setReconPort(9888)
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
      assertTrue(conf.get(OZONE_S3G_HTTP_ADDRESS_KEY).endsWith(":9878"));
      assertTrue(conf.get(OZONE_RECON_HTTP_ADDRESS_KEY).endsWith(":9888"));
      assertEquals(9860, prepared.getScmPort());
      assertEquals(9862, prepared.getOmPort());
      assertEquals(9878, prepared.getS3gPort());
      assertEquals(9888, prepared.getReconPort());
    }
  }

  @Test
  void prepareConfigurationSetsReconStorageWhenEnabled() throws Exception {
    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(
            tempDir.resolve("local-ozone"))
        .setReconEnabled(true)
        .setReconPort(9888)
        .build();

    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      OzoneConfiguration conf = cluster.prepareConfiguration().getConfiguration();

      assertTrue(conf.get(OZONE_RECON_DB_DIR)
          .startsWith(tempDir.resolve("local-ozone").resolve("recon").toString()));
      assertTrue(conf.get(OZONE_RECON_HTTP_ADDRESS_KEY).endsWith(":9888"));
    }
  }

  @Test
  void prepareConfigurationAllocatesDistinctS3AuxiliaryPorts() throws Exception {
    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(
            tempDir.resolve("local-ozone"))
        .setS3gPort(9878)
        .build();

    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      OzoneConfiguration conf = cluster.prepareConfiguration().getConfiguration();

      int httpPort = parsePort(conf.get(OZONE_S3G_HTTP_ADDRESS_KEY));
      int httpsPort = parsePort(conf.get(OZONE_S3G_HTTPS_ADDRESS_KEY));
      int webHttpPort = parsePort(conf.get(OZONE_S3G_WEBADMIN_HTTP_ADDRESS_KEY));
      int webHttpsPort = parsePort(conf.get(OZONE_S3G_WEBADMIN_HTTPS_ADDRESS_KEY));

      assertEquals(9878, httpPort);
      assertNotEquals(httpPort, httpsPort);
      assertNotEquals(httpPort, webHttpPort);
      assertNotEquals(httpPort, webHttpsPort);
      assertNotEquals(httpsPort, webHttpPort);
      assertNotEquals(httpsPort, webHttpsPort);
      assertNotEquals(webHttpPort, webHttpsPort);
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

  private static int parsePort(String address) {
    return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
  }
}
