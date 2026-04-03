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

import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_DATANODE_CLIENT_ADDRESS_KEY;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_DATANODE_CLIENT_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_DATANODE_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_DATANODE_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_INITIAL_HEARTBEAT_INTERVAL;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_RECON_INITIAL_HEARTBEAT_INTERVAL;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_SCM_SAFEMODE_MIN_DATANODE;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeState.HEALTHY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.HDDS_CONTAINER_RATIS_ENABLED_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.HDDS_DATANODE_DIR_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CLIENT_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_DATANODE_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_DATANODE_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_GRPC_PORT_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_HA_RATIS_SERVER_RPC_FIRST_ELECTION_TIMEOUT;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_NAMES;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_RATIS_PORT_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_SECURITY_SERVICE_ADDRESS_KEY;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_SECURITY_SERVICE_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_IPC_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_ADMIN_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_DATASTREAM_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_DATANODE_STORAGE_DIR;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_IPC_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_SERVER_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_RATIS_LEADER_FIRST_ELECTION_MINIMUM_TIMEOUT_DURATION_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_REPLICATION;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_REPLICATION_TYPE;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTPS_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_RATIS_MINIMUM_TIMEOUT_KEY;
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
import static org.apache.hadoop.ozone.common.Storage.StorageState.INITIALIZED;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.proxy.SCMClientConfig;
import org.apache.hadoop.hdds.scm.ha.SCMHANodeDetails;
import org.apache.hadoop.hdds.scm.ha.SCMRatisServerImpl;
import org.apache.hadoop.hdds.scm.server.SCMStorageConfig;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.OzoneSecurityUtil;
import org.apache.hadoop.ozone.container.replication.ReplicationServer;
import org.apache.hadoop.ozone.local.LocalOzoneClusterConfig.FormatMode;
import org.apache.hadoop.ozone.om.OMStorage;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.s3.Gateway;
import org.apache.hadoop.ozone.s3.OzoneConfigurationHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a small in-process local Ozone cluster.
 */
public final class LocalOzoneCluster implements AutoCloseable {

  private static final Logger LOG =
      LoggerFactory.getLogger(LocalOzoneCluster.class);

  private static final String[] NO_ARGS = new String[0];

  private final LocalOzoneClusterConfig config;
  private final OzoneConfiguration seedConfiguration;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<HddsDatanodeService> datanodes = new ArrayList<>();

  private PreparedConfiguration preparedConfiguration;
  private StorageContainerManager scm;
  private OzoneManager om;
  private Gateway s3Gateway;

  public LocalOzoneCluster(LocalOzoneClusterConfig config,
      OzoneConfiguration seedConfiguration) {
    this.config = Objects.requireNonNull(config, "config");
    this.seedConfiguration = new OzoneConfiguration(
        Objects.requireNonNull(seedConfiguration, "seedConfiguration"));
  }

  public void start() throws Exception {
    preparedConfiguration = prepareConfiguration();
    initializeStorage(preparedConfiguration.getConfiguration());

    scm = StorageContainerManager.createSCM(preparedConfiguration.getConfiguration());
    scm.start();

    om = OzoneManager.createOm(preparedConfiguration.getConfiguration());
    om.start();

    startDatanodes(preparedConfiguration.getConfiguration());

    if (config.isS3gEnabled()) {
      startS3Gateway(preparedConfiguration.getConfiguration());
    }

    waitForClusterToBeReady(config.getStartupTimeout());
  }

  PreparedConfiguration prepareConfiguration() throws IOException {
    if (preparedConfiguration != null) {
      return preparedConfiguration;
    }

    if (config.getFormatMode() == FormatMode.ALWAYS) {
      FileUtils.deleteQuietly(config.getDataDir().toFile());
    }
    Files.createDirectories(config.getDataDir());

    OzoneConfiguration conf = new OzoneConfiguration(seedConfiguration);
    conf.set(OZONE_METADATA_DIRS, createDirectory("metadata").toString());
    conf.set(OZONE_REPLICATION, ReplicationFactor.ONE.name());
    conf.set(OZONE_REPLICATION_TYPE, ReplicationType.STAND_ALONE.name());
    conf.set(OZONE_SERVER_DEFAULT_REPLICATION_KEY, ReplicationFactor.ONE.name());
    conf.set(OZONE_SERVER_DEFAULT_REPLICATION_TYPE_KEY,
        ReplicationType.STAND_ALONE.name());
    conf.setBoolean(HDDS_CONTAINER_RATIS_ENABLED_KEY, false);
    conf.setBoolean(HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);
    conf.setInt(HDDS_SCM_SAFEMODE_MIN_DATANODE, Math.max(1, config.getDatanodes()));
    conf.set(HddsConfigKeys.HDDS_SCM_WAIT_TIME_AFTER_SAFE_MODE_EXIT, "3s");
    conf.set(OZONE_SCM_HA_RATIS_SERVER_RPC_FIRST_ELECTION_TIMEOUT, "1s");
    conf.setTimeDuration(OZONE_OM_RATIS_MINIMUM_TIMEOUT_KEY, 1,
        TimeUnit.SECONDS);
    conf.set(HDDS_INITIAL_HEARTBEAT_INTERVAL, "500ms");
    conf.set(HDDS_RECON_INITIAL_HEARTBEAT_INTERVAL, "500ms");
    conf.set(HDDS_RATIS_LEADER_FIRST_ELECTION_MINIMUM_TIMEOUT_DURATION_KEY,
        "1s");

    SCMClientConfig scmClientConfig = conf.getObject(SCMClientConfig.class);
    scmClientConfig.setMaxRetryTimeout(30_000);
    conf.setFromObject(scmClientConfig);

    PortAllocator ports = new PortAllocator();
    int scmClientPort = ports.reserve(config.getScmPort());
    int scmBlockPort = ports.reserve(0);
    int scmDatanodePort = ports.reserve(0);
    int scmSecurityPort = ports.reserve(0);
    int scmHttpPort = ports.reserve(0);
    int scmHttpsPort = ports.reserve(0);
    int scmRatisPort = ports.reserve(0);
    int scmGrpcPort = ports.reserve(0);

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

    int omRpcPort = ports.reserve(config.getOmPort());
    int omHttpPort = ports.reserve(0);
    int omHttpsPort = ports.reserve(0);
    int omRatisPort = ports.reserve(0);
    conf.set(OZONE_OM_ADDRESS_KEY, address(config.getHost(), omRpcPort));
    conf.set(OZONE_OM_HTTP_ADDRESS_KEY, address(config.getHost(), omHttpPort));
    conf.set(OZONE_OM_HTTP_BIND_HOST_KEY, config.getBindHost());
    conf.set(OZONE_OM_HTTPS_ADDRESS_KEY,
        address(config.getHost(), omHttpsPort));
    conf.set(OZONE_OM_HTTPS_BIND_HOST_KEY, config.getBindHost());
    conf.setInt(OZONE_OM_RATIS_PORT_KEY, omRatisPort);

    if (config.isS3gEnabled()) {
      int s3gHttpPort = ports.reserve(config.getS3gPort());
      int s3gHttpsPort = ports.reserve(0);
      int s3gWebHttpPort = ports.reserve(0);
      int s3gWebHttpsPort = ports.reserve(0);
      conf.set(OZONE_S3G_HTTP_ADDRESS_KEY, address(config.getHost(),
          s3gHttpPort));
      conf.set(OZONE_S3G_HTTP_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_S3G_HTTPS_ADDRESS_KEY, address(config.getHost(),
          s3gHttpsPort));
      conf.set(OZONE_S3G_HTTPS_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_S3G_WEBADMIN_HTTP_ADDRESS_KEY,
          address(config.getHost(), s3gWebHttpPort));
      conf.set(OZONE_S3G_WEBADMIN_HTTP_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_S3G_WEBADMIN_HTTPS_ADDRESS_KEY,
          address(config.getHost(), s3gWebHttpsPort));
      conf.set(OZONE_S3G_WEBADMIN_HTTPS_BIND_HOST_KEY, config.getBindHost());
      preparedConfiguration = new PreparedConfiguration(conf, scmClientPort,
          omRpcPort, s3gHttpPort);
    } else {
      preparedConfiguration = new PreparedConfiguration(conf, scmClientPort,
          omRpcPort, -1);
    }
    return preparedConfiguration;
  }

  public int getScmPort() {
    return scm != null ? scm.getClientRpcAddress().getPort()
        : preparedConfiguration.getScmPort();
  }

  public int getOmPort() {
    return om != null ? om.getOmRpcServerAddr().getPort()
        : preparedConfiguration.getOmPort();
  }

  public int getS3gPort() {
    if (!config.isS3gEnabled()) {
      return -1;
    }
    return s3Gateway != null ? s3Gateway.getHttpAddress().getPort()
        : preparedConfiguration.getS3gPort();
  }

  public String getDisplayHost() {
    return "0.0.0.0".equals(config.getHost()) ? LocalOzoneClusterConfig.DEFAULT_HOST
        : config.getHost();
  }

  public String getS3Endpoint() {
    if (!config.isS3gEnabled()) {
      return "";
    }
    return "http://" + getDisplayHost() + ":" + getS3gPort();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    OzoneConfigurationHolder.resetConfiguration();
    stopQuietly(s3Gateway, "S3 gateway", gateway -> gateway.stop());
    stopDatanodes();
    stopQuietly(om, "Ozone Manager", manager -> {
      if (manager.stop()) {
        manager.join();
      }
    });
    stopQuietly(scm, "SCM", manager -> {
      manager.stop();
      manager.join();
    });

    if (config.isEphemeral()) {
      FileUtils.deleteQuietly(config.getDataDir().toFile());
    }
  }

  private void initializeStorage(OzoneConfiguration conf) throws Exception {
    SCMStorageConfig scmStorage = new SCMStorageConfig(conf);
    OMStorage omStorage = new OMStorage(conf);

    String clusterId = existingClusterId(scmStorage, omStorage);
    if (scmStorage.getState() != INITIALIZED) {
      requireFormatting("SCM");
      String scmId = UUID.randomUUID().toString();
      scmStorage.setClusterId(clusterId);
      scmStorage.setScmId(scmId);
      scmStorage.initialize();
      scmStorage.setSCMHAFlag(true);
      scmStorage.persistCurrentState();
      SCMRatisServerImpl.initialize(clusterId, scmId,
          SCMHANodeDetails.loadSCMHAConfig(conf, scmStorage)
              .getLocalNodeDetails(), conf);
    }

    if (omStorage.getState() != INITIALIZED) {
      requireFormatting("OM");
      omStorage.setClusterId(clusterId);
      omStorage.setOmId(UUID.randomUUID().toString());
      if (OzoneSecurityUtil.isSecurityEnabled(conf)) {
        OzoneManager.initializeSecurity(conf, omStorage, scmStorage.getScmId());
      }
      omStorage.initialize();
    } else if (!clusterId.equals(omStorage.getClusterID())) {
      throw new IOException("OM metadata belongs to a different cluster ID: "
          + omStorage.getClusterID());
    }
  }

  private void startDatanodes(OzoneConfiguration baseConf) throws IOException {
    for (int index = 0; index < config.getDatanodes(); index++) {
      OzoneConfiguration dnConf = new OzoneConfiguration(baseConf);
      Path datanodeDir = createDirectory("datanode-" + (index + 1));
      Path metaDir = Files.createDirectories(datanodeDir.resolve("metadata"));
      dnConf.set(OZONE_METADATA_DIRS, metaDir.toString());

      Path dataDir = Files.createDirectories(datanodeDir.resolve("data-0"));
      dnConf.set(HDDS_DATANODE_DIR_KEY, dataDir.toString());

      Path ratisDir = Files.createDirectories(datanodeDir.resolve("ratis"));
      dnConf.set(HDDS_CONTAINER_RATIS_DATANODE_STORAGE_DIR, ratisDir.toString());

      PortAllocator ports = new PortAllocator();
      dnConf.set(HDDS_DATANODE_HTTP_ADDRESS_KEY,
          address(config.getHost(), ports.reserve(0)));
      dnConf.set(HDDS_DATANODE_HTTP_BIND_HOST_KEY, config.getBindHost());
      dnConf.set(HDDS_DATANODE_CLIENT_ADDRESS_KEY,
          address(config.getHost(), ports.reserve(0)));
      dnConf.set(HDDS_DATANODE_CLIENT_BIND_HOST_KEY, config.getBindHost());
      dnConf.setInt(HDDS_CONTAINER_IPC_PORT, ports.reserve(0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_IPC_PORT, ports.reserve(0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_ADMIN_PORT, ports.reserve(0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_SERVER_PORT, ports.reserve(0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_DATASTREAM_PORT, ports.reserve(0));
      dnConf.setFromObject(new ReplicationServer.ReplicationConfig()
          .setPort(ports.reserve(0)));

      HddsDatanodeService datanode = new HddsDatanodeService(NO_ARGS);
      datanode.setConfiguration(dnConf);
      datanode.start(dnConf);
      datanodes.add(datanode);
    }
  }

  private void startS3Gateway(OzoneConfiguration baseConf) throws Exception {
    OzoneConfigurationHolder.resetConfiguration();
    OzoneConfigurationHolder.setConfiguration(new OzoneConfiguration(baseConf));
    s3Gateway = new Gateway();
    int exitCode = s3Gateway.execute(NO_ARGS);
    if (exitCode != 0) {
      throw new IOException("Failed to start local S3 gateway. Exit code="
          + exitCode);
    }
  }

  private void waitForClusterToBeReady(Duration timeout)
      throws TimeoutException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (scm != null && scm.checkLeader()
          && scm.getNodeCount(HEALTHY) == datanodes.size()
          && !scm.isInSafeMode()) {
        return;
      }
      Thread.sleep(1_000L);
    }
    throw new TimeoutException("Timed out waiting for local Ozone cluster to "
        + "become ready after " + timeout);
  }

  private void requireFormatting(String component) throws IOException {
    if (config.getFormatMode() == FormatMode.NEVER) {
      throw new IOException(component + " storage is not initialized under "
          + config.getDataDir() + ". Use --format if-needed or --format always.");
    }
  }

  private String existingClusterId(SCMStorageConfig scmStorage,
      OMStorage omStorage) {
    if (scmStorage.getState() == INITIALIZED) {
      return scmStorage.getClusterID();
    }
    if (omStorage.getState() == INITIALIZED) {
      return omStorage.getClusterID();
    }
    return UUID.randomUUID().toString();
  }

  private Path createDirectory(String name) throws IOException {
    return Files.createDirectories(config.getDataDir().resolve(name));
  }

  private void stopDatanodes() {
    for (int index = datanodes.size() - 1; index >= 0; index--) {
      HddsDatanodeService datanode = datanodes.get(index);
      stopQuietly(datanode, "Datanode", service -> {
        service.stop();
        service.join();
      });
    }
    datanodes.clear();
  }

  private static String address(String host, int port) {
    return host + ":" + port;
  }

  private static <T> void stopQuietly(T service, String name,
      ThrowingConsumer<T> stopAction) {
    if (service == null) {
      return;
    }
    try {
      stopAction.accept(service);
    } catch (Exception ex) {
      LOG.warn("Failed to stop {}", name, ex);
    }
  }

  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T value) throws Exception;
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
   * Small production-safe free-port allocator for local runtime setup.
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
}
