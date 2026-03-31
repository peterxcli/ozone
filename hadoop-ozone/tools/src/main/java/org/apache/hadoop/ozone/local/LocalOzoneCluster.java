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

import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_SCM_SAFEMODE_MIN_DATANODE;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CLIENT_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_DATANODE_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_DATANODE_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_GRPC_PORT_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_NAMES;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_RATIS_PORT_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_SECURITY_SERVICE_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_SECURITY_SERVICE_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_REPLICATION;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_REPLICATION_TYPE;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_RATIS_PORT_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_SERVER_DEFAULT_REPLICATION_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_SERVER_DEFAULT_REPLICATION_TYPE_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTPS_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTPS_BIND_HOST_KEY;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;

/**
 * Prepares a local single-node Ozone runtime configuration.
 */
public final class LocalOzoneCluster implements LocalOzoneRuntime {

  private static final String PORTS_STATE_FILE = "ports.properties";

  private final LocalOzoneClusterConfig config;
  private final OzoneConfiguration seedConfiguration;

  private PreparedConfiguration preparedConfiguration;

  public LocalOzoneCluster(LocalOzoneClusterConfig config,
      OzoneConfiguration seedConfiguration) {
    this.config = Objects.requireNonNull(config, "config");
    this.seedConfiguration = new OzoneConfiguration(
        Objects.requireNonNull(seedConfiguration, "seedConfiguration"));
  }

  @Override
  public void start() throws Exception {
    prepareConfiguration();
  }

  PreparedConfiguration prepareConfiguration() throws IOException {
    if (preparedConfiguration != null) {
      return preparedConfiguration;
    }

    Files.createDirectories(config.getDataDir());

    OzoneConfiguration conf = new OzoneConfiguration(seedConfiguration);
    conf.set(OZONE_METADATA_DIRS, createDirectory("metadata").toString());
    conf.set(OZONE_REPLICATION, ReplicationFactor.ONE.name());
    conf.set(OZONE_REPLICATION_TYPE, ReplicationType.STAND_ALONE.name());
    conf.set(OZONE_SERVER_DEFAULT_REPLICATION_KEY, ReplicationFactor.ONE.name());
    conf.set(OZONE_SERVER_DEFAULT_REPLICATION_TYPE_KEY,
        ReplicationType.STAND_ALONE.name());
    conf.setBoolean(ScmConfigKeys.HDDS_CONTAINER_RATIS_ENABLED_KEY, false);
    conf.setBoolean(HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);
    conf.setInt(HDDS_SCM_SAFEMODE_MIN_DATANODE, Math.max(1, config.getDatanodes()));

    PersistedPorts persistedPorts = PersistedPorts.load(
        config.getDataDir().resolve(PORTS_STATE_FILE));
    PortAllocator ports = new PortAllocator();
    int scmClientPort = reservePort(ports, persistedPorts,
        "scm.client", config.getScmPort());
    int scmBlockPort = reservePort(ports, persistedPorts, "scm.block", 0);
    int scmDatanodePort = reservePort(ports, persistedPorts, "scm.datanode", 0);
    int scmSecurityPort = reservePort(ports, persistedPorts, "scm.security", 0);
    int scmHttpPort = reservePort(ports, persistedPorts, "scm.http", 0);
    int scmHttpsPort = reservePort(ports, persistedPorts, "scm.https", 0);
    int scmRatisPort = reservePort(ports, persistedPorts, "scm.ratis", 0);
    int scmGrpcPort = reservePort(ports, persistedPorts, "scm.grpc", 0);
    conf.set(OZONE_SCM_CLIENT_ADDRESS_KEY, address(config.getHost(), scmClientPort));
    conf.set(OZONE_SCM_CLIENT_BIND_HOST_KEY, config.getBindHost());
    conf.set(OZONE_SCM_BLOCK_CLIENT_ADDRESS_KEY,
        address(config.getHost(), scmBlockPort));
    conf.set(OZONE_SCM_BLOCK_CLIENT_BIND_HOST_KEY, config.getBindHost());
    conf.set(OZONE_SCM_DATANODE_ADDRESS_KEY,
        address(config.getHost(), scmDatanodePort));
    conf.set(OZONE_SCM_DATANODE_BIND_HOST_KEY, config.getBindHost());
    conf.set(OZONE_SCM_SECURITY_SERVICE_ADDRESS_KEY,
        address(config.getHost(), scmSecurityPort));
    conf.set(OZONE_SCM_SECURITY_SERVICE_BIND_HOST_KEY, config.getBindHost());
    conf.set(OZONE_SCM_HTTP_ADDRESS_KEY, address(config.getHost(), scmHttpPort));
    conf.set(OZONE_SCM_HTTP_BIND_HOST_KEY, config.getBindHost());
    conf.set(ScmConfigKeys.OZONE_SCM_HTTPS_ADDRESS_KEY,
        address(config.getHost(), scmHttpsPort));
    conf.set(ScmConfigKeys.OZONE_SCM_HTTPS_BIND_HOST_KEY, config.getBindHost());
    conf.setInt(OZONE_SCM_RATIS_PORT_KEY, scmRatisPort);
    conf.setInt(OZONE_SCM_GRPC_PORT_KEY, scmGrpcPort);
    conf.setStrings(OZONE_SCM_NAMES, address(config.getHost(), scmDatanodePort));

    int omRpcPort = reservePort(ports, persistedPorts, "om.rpc", config.getOmPort());
    int omHttpPort = reservePort(ports, persistedPorts, "om.http", 0);
    int omRatisPort = reservePort(ports, persistedPorts, "om.ratis", 0);
    conf.set(OZONE_OM_ADDRESS_KEY, address(config.getHost(), omRpcPort));
    conf.set(OZONE_OM_HTTP_ADDRESS_KEY, address(config.getHost(), omHttpPort));
    conf.set(OZONE_OM_HTTP_BIND_HOST_KEY, config.getBindHost());
    conf.setInt(OZONE_OM_RATIS_PORT_KEY, omRatisPort);

    int s3gPort = -1;
    if (config.isS3gEnabled()) {
      s3gPort = reservePort(ports, persistedPorts, "s3g.http", config.getS3gPort());
      int s3gHttpsPort = reservePort(ports, persistedPorts, "s3g.https", 0);
      int s3gWebHttpPort = reservePort(ports, persistedPorts, "s3g.web.http", 0);
      int s3gWebHttpsPort = reservePort(ports, persistedPorts, "s3g.web.https", 0);
      conf.set(OZONE_S3G_HTTP_ADDRESS_KEY, address(config.getHost(), s3gPort));
      conf.set(OZONE_S3G_HTTP_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_S3G_HTTPS_ADDRESS_KEY, address(config.getHost(), s3gHttpsPort));
      conf.set(OZONE_S3G_HTTPS_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_S3G_WEBADMIN_HTTP_ADDRESS_KEY,
          address(config.getHost(), s3gWebHttpPort));
      conf.set(OZONE_S3G_WEBADMIN_HTTP_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_S3G_WEBADMIN_HTTPS_ADDRESS_KEY,
          address(config.getHost(), s3gWebHttpsPort));
      conf.set(OZONE_S3G_WEBADMIN_HTTPS_BIND_HOST_KEY, config.getBindHost());
    }

    persistedPorts.store();
    preparedConfiguration = new PreparedConfiguration(conf, scmClientPort,
        omRpcPort, s3gPort);
    return preparedConfiguration;
  }

  @Override
  public int getScmPort() {
    return preparedConfiguration == null ? config.getScmPort()
        : preparedConfiguration.getScmPort();
  }

  @Override
  public int getOmPort() {
    return preparedConfiguration == null ? config.getOmPort()
        : preparedConfiguration.getOmPort();
  }

  @Override
  public int getS3gPort() {
    return preparedConfiguration == null ? config.getS3gPort()
        : preparedConfiguration.getS3gPort();
  }

  @Override
  public String getDisplayHost() {
    return "0.0.0.0".equals(config.getHost())
        ? LocalOzoneClusterConfig.DEFAULT_HOST : config.getHost();
  }

  @Override
  public String getS3Endpoint() {
    return config.isS3gEnabled()
        ? "http://" + getDisplayHost() + ":" + getS3gPort() : "";
  }

  @Override
  public void close() {
  }

  private Path createDirectory(String name) throws IOException {
    return Files.createDirectories(config.getDataDir().resolve(name));
  }

  private int reservePort(PortAllocator allocator, PersistedPorts persistedPorts,
      String key, int configuredPort) throws IOException {
    int preferredPort = configuredPort > 0 ? configuredPort
        : persistedPorts.get(key);
    int port = allocator.reserve(preferredPort);
    persistedPorts.set(key, port);
    return port;
  }

  private static String address(String host, int port) {
    return host + ":" + port;
  }

  static final class PreparedConfiguration {
    private final OzoneConfiguration configuration;
    private final int scmPort;
    private final int omPort;
    private final int s3gPort;

    PreparedConfiguration(OzoneConfiguration configuration, int scmPort,
        int omPort, int s3gPort) {
      this.configuration = configuration;
      this.scmPort = scmPort;
      this.omPort = omPort;
      this.s3gPort = s3gPort;
    }

    OzoneConfiguration getConfiguration() {
      return configuration;
    }

    int getScmPort() {
      return scmPort;
    }

    int getOmPort() {
      return omPort;
    }

    int getS3gPort() {
      return s3gPort;
    }
  }

  /**
   * Small free-port allocator for local runtime setup.
   */
  static final class PortAllocator {
    private final Set<Integer> reserved = new HashSet<>();

    int reserve(int preferredPort) throws IOException {
      if (preferredPort > 0) {
        if (!reserved.add(preferredPort)) {
          throw new IOException("Port " + preferredPort
              + " is configured more than once.");
        }
        return preferredPort;
      }

      while (true) {
        int candidate = nextFreePort();
        if (reserved.add(candidate)) {
          return candidate;
        }
      }
    }

    private static int nextFreePort() throws IOException {
      try (ServerSocket socket = new ServerSocket(0)) {
        socket.setReuseAddress(false);
        return socket.getLocalPort();
      }
    }
  }

  static final class PersistedPorts {
    private final Path path;
    private final Properties properties = new Properties();

    private PersistedPorts(Path path) {
      this.path = path;
    }

    static PersistedPorts load(Path path) throws IOException {
      PersistedPorts persistedPorts = new PersistedPorts(path);
      if (Files.exists(path)) {
        try (InputStream input = Files.newInputStream(path)) {
          persistedPorts.properties.load(input);
        }
      }
      return persistedPorts;
    }

    int get(String key) {
      String value = properties.getProperty(key);
      return value == null ? 0 : Integer.parseInt(value);
    }

    void set(String key, int port) {
      properties.setProperty(key, Integer.toString(port));
    }

    void store() throws IOException {
      try (OutputStream output = Files.newOutputStream(path)) {
        properties.store(output, "Local Ozone reserved ports");
      }
    }
  }
}
