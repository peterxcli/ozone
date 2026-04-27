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
import static org.apache.hadoop.hdds.recon.ReconConfigKeys.OZONE_RECON_ADDRESS_KEY;
import static org.apache.hadoop.hdds.recon.ReconConfigKeys.OZONE_RECON_DATANODE_ADDRESS_KEY;
import static org.apache.hadoop.hdds.recon.ReconConfigKeys.OZONE_RECON_DATANODE_BIND_HOST_KEY;
import static org.apache.hadoop.hdds.recon.ReconConfigKeys.OZONE_RECON_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.hdds.recon.ReconConfigKeys.OZONE_RECON_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.hdds.recon.ReconConfigKeys.OZONE_RECON_TASK_SAFEMODE_WAIT_THRESHOLD;
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
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_DATANODE_STORAGE_DIR;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_DATASTREAM_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_IPC_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_CONTAINER_RATIS_SERVER_PORT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.HDDS_RATIS_LEADER_FIRST_ELECTION_MINIMUM_TIMEOUT_DURATION_KEY;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_REPLICATION;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_REPLICATION_TYPE;
import static org.apache.hadoop.ozone.common.Storage.StorageState.INITIALIZED;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTPS_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_RATIS_MINIMUM_TIMEOUT_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_RATIS_PORT_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_SERVER_DEFAULT_REPLICATION_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_SERVER_DEFAULT_REPLICATION_TYPE_KEY;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_DB_DIR;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_HTTPS_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_OM_SNAPSHOT_DB_DIR;
import static org.apache.hadoop.ozone.recon.ReconServerConfigKeys.OZONE_RECON_SCM_DB_DIR;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTPS_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTPS_BIND_HOST_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.ozone.s3.S3GatewayConfigKeys.OZONE_S3G_WEBADMIN_HTTP_BIND_HOST_KEY;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.ha.SCMHANodeDetails;
import org.apache.hadoop.hdds.scm.ha.SCMRatisServerImpl;
import org.apache.hadoop.hdds.scm.proxy.SCMClientConfig;
import org.apache.hadoop.hdds.scm.server.SCMStorageConfig;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.OzoneSecurityUtil;
import org.apache.hadoop.ozone.container.replication.ReplicationServer;
import org.apache.hadoop.ozone.local.LocalOzoneClusterConfig.FormatMode;
import org.apache.hadoop.ozone.om.OMStorage;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.helpers.S3SecretValue;
import org.apache.hadoop.ozone.recon.ConfigurationProvider;
import org.apache.hadoop.ozone.recon.ReconServer;
import org.apache.hadoop.ozone.recon.ReconSqlDbConfig;
import org.apache.hadoop.ozone.s3.Gateway;
import org.apache.hadoop.ozone.s3.OzoneConfigurationHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a small in-process local Ozone runtime.
 */
public final class LocalOzoneCluster implements LocalOzoneRuntime {

  private static final Logger LOG =
      LoggerFactory.getLogger(LocalOzoneCluster.class);

  private static final String[] NO_ARGS = new String[0];
  private static final String PORTS_STATE_FILE = "ports.properties";

  private final LocalOzoneClusterConfig config;
  private final OzoneConfiguration seedConfiguration;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<HddsDatanodeService> datanodes = new ArrayList<>();

  private PreparedConfiguration preparedConfiguration;
  private StorageContainerManager scm;
  private OzoneManager om;
  private Gateway s3Gateway;
  private ReconServer reconServer;
  private boolean previousMetricsMiniClusterMode;
  private boolean metricsMiniClusterModeEnabled;

  public LocalOzoneCluster(LocalOzoneClusterConfig config,
      OzoneConfiguration seedConfiguration) {
    this.config = Objects.requireNonNull(config, "config");
    this.seedConfiguration = new OzoneConfiguration(
        Objects.requireNonNull(seedConfiguration, "seedConfiguration"));
  }

  @Override
  public void start() throws Exception {
    enableSameJvmMetricsMode();
    preparedConfiguration = prepareConfiguration();
    initializeStorage(preparedConfiguration.getConfiguration());

    scm = StorageContainerManager.createSCM(preparedConfiguration.getConfiguration());
    scm.start();

    om = OzoneManager.createOm(preparedConfiguration.getConfiguration());
    om.start();

    startDatanodes(preparedConfiguration.getConfiguration());
    waitForClusterToBeReady(config.getStartupTimeout());

    if (config.isReconEnabled()) {
      startRecon(preparedConfiguration.getConfiguration());
      waitForReconToBeReady(config.getStartupTimeout());
    }
    if (config.isS3gEnabled()) {
      provisionS3Credentials();
      startS3Gateway(preparedConfiguration.getConfiguration());
      waitForS3GatewayToBeReady(config.getStartupTimeout());
    }
  }

  PreparedConfiguration prepareConfiguration() throws IOException {
    if (preparedConfiguration != null) {
      return preparedConfiguration;
    }

    if (config.getFormatMode() == FormatMode.ALWAYS) {
      deleteDirectory(config.getDataDir());
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

    PersistedPorts persistedPorts = PersistedPorts.load(
        config.getDataDir().resolve(PORTS_STATE_FILE));
    PortAllocator ports = new PortAllocator();
    int scmClientPort = reservePort(ports, persistedPorts,
        "scm.client", config.getScmPort());
    int scmBlockPort = reservePort(ports, persistedPorts,
        "scm.block", 0);
    int scmDatanodePort = reservePort(ports, persistedPorts,
        "scm.datanode", 0);
    int scmSecurityPort = reservePort(ports, persistedPorts,
        "scm.security", 0);
    int scmHttpPort = reservePort(ports, persistedPorts,
        "scm.http", 0);
    int scmHttpsPort = reservePort(ports, persistedPorts,
        "scm.https", 0);
    int scmRatisPort = reservePort(ports, persistedPorts,
        "scm.ratis", 0);
    int scmGrpcPort = reservePort(ports, persistedPorts,
        "scm.grpc", 0);

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

    int omRpcPort = reservePort(ports, persistedPorts,
        "om.rpc", config.getOmPort());
    int omHttpPort = reservePort(ports, persistedPorts,
        "om.http", 0);
    int omHttpsPort = reservePort(ports, persistedPorts,
        "om.https", 0);
    int omRatisPort = reservePort(ports, persistedPorts,
        "om.ratis", 0);
    conf.set(OZONE_OM_ADDRESS_KEY, address(config.getHost(), omRpcPort));
    conf.set(OZONE_OM_HTTP_ADDRESS_KEY, address(config.getHost(), omHttpPort));
    conf.set(OZONE_OM_HTTP_BIND_HOST_KEY, config.getBindHost());
    conf.set(OZONE_OM_HTTPS_ADDRESS_KEY,
        address(config.getHost(), omHttpsPort));
    conf.set(OZONE_OM_HTTPS_BIND_HOST_KEY, config.getBindHost());
    conf.setInt(OZONE_OM_RATIS_PORT_KEY, omRatisPort);

    int reconHttpPort = -1;
    if (config.isReconEnabled()) {
      Path reconDir = createDirectory("recon");
      conf.set(OZONE_RECON_DB_DIR, reconDir.toString());
      conf.set(OZONE_RECON_OM_SNAPSHOT_DB_DIR, reconDir.toString());
      conf.set(OZONE_RECON_SCM_DB_DIR, reconDir.toString());

      ReconSqlDbConfig dbConfig = conf.getObject(ReconSqlDbConfig.class);
      dbConfig.setJdbcUrl("jdbc:derby:" + reconDir.resolve("ozone_recon_derby.db"));
      conf.setFromObject(dbConfig);

      int reconDatanodePort = reservePort(ports, persistedPorts,
          "recon.datanode", 0);
      reconHttpPort = reservePort(ports, persistedPorts,
          "recon.http", config.getReconPort());
      int reconHttpsPort = reservePort(ports, persistedPorts,
          "recon.https", 0);
      conf.set(OZONE_RECON_ADDRESS_KEY, address(config.getHost(),
          reconDatanodePort));
      conf.set(OZONE_RECON_DATANODE_ADDRESS_KEY, address(config.getHost(),
          reconDatanodePort));
      conf.set(OZONE_RECON_DATANODE_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_RECON_HTTP_ADDRESS_KEY, address(config.getHost(),
          reconHttpPort));
      conf.set(OZONE_RECON_HTTP_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_RECON_HTTPS_ADDRESS_KEY, address(config.getHost(),
          reconHttpsPort));
      conf.set(OZONE_RECON_HTTPS_BIND_HOST_KEY, config.getBindHost());
      conf.set(OZONE_RECON_TASK_SAFEMODE_WAIT_THRESHOLD, "10s");
    }

    if (config.isS3gEnabled()) {
      int s3gHttpPort = reservePort(ports, persistedPorts,
          "s3g.http", config.getS3gPort());
      int s3gHttpsPort = reservePort(ports, persistedPorts,
          "s3g.https", 0);
      int s3gWebHttpPort = reservePort(ports, persistedPorts,
          "s3g.web.http", 0);
      int s3gWebHttpsPort = reservePort(ports, persistedPorts,
          "s3g.web.https", 0);
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
          omRpcPort, s3gHttpPort, reconHttpPort);
    } else {
      preparedConfiguration = new PreparedConfiguration(conf, scmClientPort,
          omRpcPort, -1, reconHttpPort);
    }
    persistedPorts.store();
    return preparedConfiguration;
  }

  @Override
  public int getScmPort() {
    return scm != null ? scm.getClientRpcAddress().getPort()
        : preparedConfiguration.getScmPort();
  }

  @Override
  public int getOmPort() {
    return om != null ? om.getOmRpcServerAddr().getPort()
        : preparedConfiguration.getOmPort();
  }

  @Override
  public int getS3gPort() {
    if (!config.isS3gEnabled()) {
      return -1;
    }
    return s3Gateway != null ? s3Gateway.getHttpAddress().getPort()
        : preparedConfiguration.getS3gPort();
  }

  @Override
  public int getReconPort() {
    if (!config.isReconEnabled()) {
      return -1;
    }
    return preparedConfiguration.getReconPort();
  }

  @Override
  public String getDisplayHost() {
    return "0.0.0.0".equals(config.getHost()) ? LocalOzoneClusterConfig.DEFAULT_HOST
        : config.getHost();
  }

  @Override
  public String getS3Endpoint() {
    if (!config.isS3gEnabled()) {
      return "";
    }
    return "http://" + getDisplayHost() + ":" + getS3gPort();
  }

  @Override
  public String getReconEndpoint() {
    if (!config.isReconEnabled()) {
      return "";
    }
    return "http://" + getDisplayHost() + ":" + getReconPort();
  }

  int getStartedDatanodeCount() {
    return datanodes.size();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    try {
      OzoneConfigurationHolder.resetConfiguration();
      stopQuietly(reconServer, "Recon", server -> {
        server.stop();
        server.join();
      });
      ConfigurationProvider.resetConfiguration();
      stopQuietly(s3Gateway, "S3 gateway", Gateway::stop);
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
        try {
          deleteDirectory(config.getDataDir());
        } catch (IOException ex) {
          throw new IllegalStateException("Failed to delete ephemeral local "
              + "Ozone data dir " + config.getDataDir(), ex);
        }
      }
    } finally {
      restoreMetricsMode();
    }
  }

  private void enableSameJvmMetricsMode() {
    if (!metricsMiniClusterModeEnabled) {
      previousMetricsMiniClusterMode = DefaultMetricsSystem.inMiniClusterMode();
      DefaultMetricsSystem.setMiniClusterMode(true);
      metricsMiniClusterModeEnabled = true;
    }
  }

  private void restoreMetricsMode() {
    if (metricsMiniClusterModeEnabled) {
      DefaultMetricsSystem.setMiniClusterMode(previousMetricsMiniClusterMode);
      metricsMiniClusterModeEnabled = false;
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

      PersistedPorts persistedPorts = PersistedPorts.load(
          config.getDataDir().resolve(PORTS_STATE_FILE));
      PortAllocator ports = new PortAllocator();
      dnConf.set(HDDS_DATANODE_HTTP_ADDRESS_KEY,
          address(config.getHost(), reservePort(ports, persistedPorts,
              "dn." + index + ".http", 0)));
      dnConf.set(HDDS_DATANODE_HTTP_BIND_HOST_KEY, config.getBindHost());
      dnConf.set(HDDS_DATANODE_CLIENT_ADDRESS_KEY,
          address(config.getHost(), reservePort(ports, persistedPorts,
              "dn." + index + ".client", 0)));
      dnConf.set(HDDS_DATANODE_CLIENT_BIND_HOST_KEY, config.getBindHost());
      dnConf.setInt(HDDS_CONTAINER_IPC_PORT, reservePort(ports, persistedPorts,
          "dn." + index + ".container.ipc", 0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_IPC_PORT,
          reservePort(ports, persistedPorts, "dn." + index + ".ratis.ipc", 0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_ADMIN_PORT,
          reservePort(ports, persistedPorts, "dn." + index + ".ratis.admin",
              0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_SERVER_PORT,
          reservePort(ports, persistedPorts, "dn." + index + ".ratis.server",
              0));
      dnConf.setInt(HDDS_CONTAINER_RATIS_DATASTREAM_PORT,
          reservePort(ports, persistedPorts,
              "dn." + index + ".ratis.datastream", 0));
      dnConf.setFromObject(new ReplicationServer.ReplicationConfig()
          .setPort(reservePort(ports, persistedPorts,
              "dn." + index + ".replication", 0)));
      persistedPorts.store();

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

  private void startRecon(OzoneConfiguration baseConf) throws IOException {
    ConfigurationProvider.resetConfiguration();
    ConfigurationProvider.setConfiguration(new OzoneConfiguration(baseConf));
    reconServer = new ReconServer();
    int exitCode = reconServer.execute(NO_ARGS);
    if (exitCode != 0) {
      throw new IOException("Failed to start local Recon. Exit code="
          + exitCode);
    }
  }

  private void provisionS3Credentials() throws IOException {
    om.getS3SecretManager().storeSecret(config.getS3AccessKey(),
        S3SecretValue.of(config.getS3AccessKey(), config.getS3SecretKey()));
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

  private void waitForS3GatewayToBeReady(Duration timeout)
      throws TimeoutException, InterruptedException {
    waitForHttpEndpointToBeReady(getS3Endpoint(), "local S3 gateway", timeout);
  }

  private void waitForReconToBeReady(Duration timeout)
      throws TimeoutException, InterruptedException {
    waitForHttpEndpointToBeReady(getReconEndpoint(), "local Recon", timeout);
  }

  private void waitForHttpEndpointToBeReady(String endpoint, String name,
      Duration timeout) throws TimeoutException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(1_000);
        connection.setReadTimeout(1_000);
        connection.setRequestMethod("GET");
        connection.getResponseCode();
        return;
      } catch (IOException ex) {
        Thread.sleep(1_000L);
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
      }
    }
    throw new TimeoutException("Timed out waiting for " + name
        + " to become ready after " + timeout);
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

  private static void deleteDirectory(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(path);
      }
    }
  }

  private int reservePort(PortAllocator allocator, PersistedPorts persistedPorts,
      String key, int configuredPort) throws IOException {
    int preferredPort = configuredPort > 0 ? configuredPort
        : persistedPorts.get(key);
    int port = allocator.reserve(preferredPort);
    persistedPorts.set(key, port);
    return port;
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
    private final int reconPort;

    PreparedConfiguration(OzoneConfiguration configuration, int scmPort,
        int omPort, int s3gPort, int reconPort) {
      this.configuration = configuration;
      this.scmPort = scmPort;
      this.omPort = omPort;
      this.s3gPort = s3gPort;
      this.reconPort = reconPort;
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

    int getReconPort() {
      return reconPort;
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
