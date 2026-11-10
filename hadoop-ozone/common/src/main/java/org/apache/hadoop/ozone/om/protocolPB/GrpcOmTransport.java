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

package org.apache.hadoop.ozone.om.protocolPB;

import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_GRPC_MAXIMUM_RESPONSE_LENGTH;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_GRPC_MAXIMUM_RESPONSE_LENGTH_DEFAULT;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.hadoop.hdds.conf.Config;
import org.apache.hadoop.hdds.conf.ConfigGroup;
import org.apache.hadoop.hdds.conf.ConfigTag;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.security.SecurityConfig;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes;
import org.apache.hadoop.ozone.om.ha.GrpcOMFailoverProxyProvider;
import org.apache.hadoop.ozone.om.ha.GrpcOMFollowerReadFailoverProxyProvider;
import org.apache.hadoop.ozone.om.helpers.ReadConsistency;
import org.apache.hadoop.ozone.om.protocolPB.grpc.ClientAddressClientInterceptor;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerServiceGrpc;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grpc transport for grpc between s3g and om.
 */
public class GrpcOmTransport implements OmTransport {
  private static final Logger LOG =
      LoggerFactory.getLogger(GrpcOmTransport.class);

  /**
   * RpcController is not used and hence is set to null.
   */
  private static final RpcController NULL_RPC_CONTROLLER = null;

  private static final String CLIENT_NAME = "GrpcOmTransport";
  private static final int SHUTDOWN_WAIT_INTERVAL = 100;
  private static final int SHUTDOWN_MAX_WAIT_SECONDS = 5;
  private final AtomicBoolean isRunning = new AtomicBoolean(false);

  // gRPC specific
  private static List<X509Certificate> caCerts = null;

  private final Map<String,
      OzoneManagerServiceGrpc.OzoneManagerServiceBlockingStub> clients;
  private final Map<String, ManagedChannel> channels;
  private final int maxSize;
  private final SecurityConfig secConfig;

  private final GrpcOMFailoverProxyProvider omFailoverProxyProvider;
  private final GrpcOMFollowerReadFailoverProxyProvider followerReadFailoverProxyProvider;
  private final OzoneManagerProtocolPB rpcProxy;

  public static void setCaCerts(List<X509Certificate> x509Certificates) {
    caCerts = x509Certificates;
  }

  public GrpcOmTransport(ConfigurationSource conf,
                          UserGroupInformation ugi, String omServiceId)
      throws IOException {

    this.channels = new HashMap<>();
    this.clients = new HashMap<>();

    secConfig =  new SecurityConfig(conf);
    maxSize = conf.getInt(OZONE_OM_GRPC_MAXIMUM_RESPONSE_LENGTH,
        OZONE_OM_GRPC_MAXIMUM_RESPONSE_LENGTH_DEFAULT);

    omFailoverProxyProvider = new GrpcOMFailoverProxyProvider(
        conf,
        ugi,
        omServiceId,
        clients::get);

    boolean defaultFollowerReadEnabled = conf.getBoolean(
        OzoneConfigKeys.OZONE_CLIENT_FOLLOWER_READ_ENABLED_KEY,
        OzoneConfigKeys.OZONE_CLIENT_FOLLOWER_READ_ENABLED_DEFAULT);
    String defaultFollowerReadConsistencyStr = conf.get(
        OzoneConfigKeys.OZONE_CLIENT_FOLLOWER_READ_DEFAULT_CONSISTENCY_KEY,
        OzoneConfigKeys.OZONE_CLIENT_FOLLOWER_READ_DEFAULT_CONSISTENCY_DEFAULT
    );
    ReadConsistency defaultFollowerReadConsistency =
        ReadConsistency.valueOf(defaultFollowerReadConsistencyStr);
    String defaultLeaderReadConsistencyStr = conf.get(
        OzoneConfigKeys.OZONE_CLIENT_LEADER_READ_DEFAULT_CONSISTENCY_KEY,
        OzoneConfigKeys.OZONE_CLIENT_LEADER_READ_DEFAULT_CONSISTENCY_DEFAULT);
    ReadConsistency defaultLeaderReadConsistency =
        ReadConsistency.valueOf(defaultLeaderReadConsistencyStr);
    this.followerReadFailoverProxyProvider = new GrpcOMFollowerReadFailoverProxyProvider(omFailoverProxyProvider,
        defaultFollowerReadConsistency, defaultLeaderReadConsistency, defaultFollowerReadEnabled);
    int maxFailovers = conf.getInt(
        OzoneConfigKeys.OZONE_CLIENT_FAILOVER_MAX_ATTEMPTS_KEY,
        OzoneConfigKeys.OZONE_CLIENT_FAILOVER_MAX_ATTEMPTS_DEFAULT);
    this.rpcProxy = OzoneManagerProtocolPB.newProxy(followerReadFailoverProxyProvider, maxFailovers);

    start();
  }

  public void start() throws IOException {
    if (!isRunning.compareAndSet(false, true)) {
      LOG.info("Ignore. already started.");
      return;
    }

    for (String nodeId : omFailoverProxyProvider.getOMProxyMap().getNodeIds()) {
      String hostaddr = omFailoverProxyProvider.getGrpcProxyAddress(nodeId);
      ManagedChannel channel = createChannel(hostaddr);
      channels.put(hostaddr, channel);
      clients.put(hostaddr, OzoneManagerServiceGrpc.newBlockingStub(channel));
    }
    LOG.info("{}: started", CLIENT_NAME);
  }

  private ManagedChannel createChannel(String hostaddr) {
    HostAndPort hp = HostAndPort.fromString(hostaddr);

    NettyChannelBuilder channelBuilder =
        NettyChannelBuilder.forAddress(hp.getHost(), hp.getPort())
            .usePlaintext()
            .proxyDetector(uri -> null)
            .maxInboundMessageSize(maxSize);

    if (secConfig.isSecurityEnabled() && secConfig.isGrpcTlsEnabled()) {
      try {
        SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
        if (caCerts != null) {
          sslContextBuilder.trustManager(caCerts);
        } else {
          LOG.error("x509Certificates empty");
        }
        channelBuilder.useTransportSecurity().
            sslContext(sslContextBuilder.build());
      } catch (Exception ex) {
        LOG.error("cannot establish TLS for grpc om transport client");
      }
    } else {
      channelBuilder.usePlaintext();
    }

    return channelBuilder.intercept(new ClientAddressClientInterceptor()).build();
  }

  @Override
  public OMResponse submitRequest(OMRequest payload) throws IOException {
    try {
      return rpcProxy.submitRequest(NULL_RPC_CONTROLLER, payload);
    } catch (ServiceException e) {
      throw toOMException(e);
    }
  }

  /**
   * @return the exception for a request that has failed after all the retries.
   *         An {@link OMException}, such as {@link ResultCodes#SSL_CONNECTION_FAILURE}, is returned as is.
   *         Any other failure becomes {@link ResultCodes#TIMEOUT} if the OM is unavailable,
   *         otherwise {@link ResultCodes#INTERNAL_ERROR}.
   */
  private static OMException toOMException(ServiceException e) {
    final Throwable cause = e.getCause();
    if (cause instanceof OMException) {
      return (OMException) cause;
    }
    final boolean unavailable = cause instanceof StatusRuntimeException
        && ((StatusRuntimeException) cause).getStatus().getCode() == Status.Code.UNAVAILABLE;
    return new OMException(cause, unavailable ? ResultCodes.TIMEOUT : ResultCodes.INTERNAL_ERROR);
  }

  // stub implementation for interface
  @Override
  public Text getDelegationTokenService() {
    return new Text();
  }

  public void shutdown() {
    for (ManagedChannel channel : channels.values()) {
      channel.shutdown();
    }

    final long maxWaitNanos = TimeUnit.SECONDS.toNanos(SHUTDOWN_MAX_WAIT_SECONDS);
    long deadline = System.nanoTime() + maxWaitNanos;
    List<ManagedChannel> nonTerminated = new ArrayList<>(channels.values());

    while (!nonTerminated.isEmpty() && System.nanoTime() < deadline) {
      nonTerminated.removeIf(ManagedChannel::isTerminated);
      if (nonTerminated.isEmpty()) {
        break;
      }
      try {
        Thread.sleep(SHUTDOWN_WAIT_INTERVAL);
      } catch (InterruptedException e) {
        LOG.error("Interrupted while waiting for channels to terminate", e);
        Thread.currentThread().interrupt();
        break;
      }
    }

    if (!nonTerminated.isEmpty()) {
      List<String> failedChannels = channels.entrySet().stream()
          .filter(e -> !e.getValue().isTerminated())
          .map(Map.Entry::getKey)
          .collect(Collectors.toList());
      LOG.warn("Channels {} did not terminate within timeout.", failedChannels);
    }

    LOG.info("{}: stopped", CLIENT_NAME);
  }

  @Override
  public void close() throws IOException {
    shutdown();
  }

  /**
   * GrpcOmTransport configuration in Java style configuration class.
   */
  @ConfigGroup(prefix = "ozone.om.grpc")
  public static final class GrpcOmTransportConfig {
    @Config(key = "ozone.om.grpc.port", defaultValue = "8981",
        description = "Port used for"
            + " the GrpcOmTransport OzoneManagerServiceGrpc server",
        tags = {ConfigTag.MANAGEMENT})
    private int port;

    public int getPort() {
      return port;
    }

    public GrpcOmTransportConfig setPort(int portParam) {
      this.port = portParam;
      return this;
    }
  }

  @VisibleForTesting
  public void startClient(ManagedChannel testChannel) throws IOException {
    for (String nodeId : omFailoverProxyProvider.getOMProxyMap().getNodeIds()) {
      String hostaddr = omFailoverProxyProvider.getGrpcProxyAddress(nodeId);

      clients.put(hostaddr,
          OzoneManagerServiceGrpc
              .newBlockingStub(testChannel));
    }
    LOG.info("{}: started", CLIENT_NAME);
  }

  @VisibleForTesting
  public void startClient(String nodeId, ManagedChannel testChannel) throws IOException {
    String hostaddr = omFailoverProxyProvider.getGrpcProxyAddress(nodeId);
    clients.put(hostaddr,
        OzoneManagerServiceGrpc
            .newBlockingStub(testChannel));
    LOG.info("{}: started test client for {}", CLIENT_NAME, nodeId);
  }

  @VisibleForTesting
  public void changeFollowerReadInitialProxy(String nodeId) {
    followerReadFailoverProxyProvider.changeInitialProxyForTest(nodeId);
  }

  @VisibleForTesting
  public void changeLeaderProxyForTest(String nodeId) throws IOException {
    omFailoverProxyProvider.setNextOmProxy(nodeId);
    omFailoverProxyProvider.performFailover(null);
  }

}
