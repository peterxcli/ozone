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

package org.apache.hadoop.ozone.om.ha;

import static org.apache.hadoop.hdds.HddsUtils.getHostNameFromConfigKeys;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.SSL_CONNECTION_FAILURE;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import javax.net.ssl.SSLHandshakeException;
import org.apache.hadoop.hdds.HddsUtils;
import org.apache.hadoop.hdds.conf.ConfigurationException;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.ha.ConfUtils;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.protocolPB.GrpcOmTransport;
import org.apache.hadoop.ozone.om.protocolPB.OzoneManagerProtocolPB;
import org.apache.hadoop.ozone.om.protocolPB.grpc.GrpcClientConstants;
import org.apache.hadoop.ozone.om.protocolPB.grpc.OMGrpcError;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerServiceGrpc.OzoneManagerServiceBlockingStub;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Grpc s3gateway om transport failover proxy provider implementation
 * extending the ozone client OM failover proxy provider.  This implementation
 * allows the Grpc OMTransport reuse OM failover retry policies and
 * getRetryAction methods.  In case of OM failover, client can try
 * connecting to another OM node from the list of proxies.
 */
public class GrpcOMFailoverProxyProvider extends
    OMFailoverProxyProviderBase<OzoneManagerProtocolPB> {
  private static final Logger LOG =
      LoggerFactory.getLogger(GrpcOMFailoverProxyProvider.class);

  /** The gRPC stub of the OM at a given gRPC address. */
  private final Function<String, OzoneManagerServiceBlockingStub> stubs;

  public GrpcOMFailoverProxyProvider(ConfigurationSource configuration,
                                     UserGroupInformation ugi,
                                     String omServiceId,
                                     Function<String, OzoneManagerServiceBlockingStub> stubs) throws IOException {
    super(configuration, ugi, omServiceId, OzoneManagerProtocolPB.class);
    this.stubs = stubs;
  }

  @Override
  protected List<OMProxyInfo<OzoneManagerProtocolPB>> initOmProxiesFromConfigs(ConfigurationSource config,
      String omSvcId) throws IOException {

    Collection<String> omNodeIds = OmUtils.getActiveNonListenerOMNodeIds(config, omSvcId);
    final List<OMProxyInfo<OzoneManagerProtocolPB>> omProxies = new ArrayList<>();

    for (String nodeId : OmUtils.emptyAsSingletonNull(omNodeIds)) {
      String rpcAddrKey = ConfUtils.addKeySuffixes(OZONE_OM_ADDRESS_KEY,
          omSvcId, nodeId);
      Optional<String> hostAddr = getHostNameFromConfigKeys(config,
          rpcAddrKey);
      OptionalInt hostport = HddsUtils.getNumberFromConfigKeys(config,
          ConfUtils.addKeySuffixes(OMConfigKeys.OZONE_OM_GRPC_PORT_KEY,
              omSvcId, nodeId),
          OMConfigKeys.OZONE_OM_GRPC_PORT_KEY);
      if (hostAddr.isPresent()) {
        int port = hostport
            .orElse(config.getObject(GrpcOmTransport.GrpcOmTransportConfig.class).getPort());
        String rpcAddrStr = HddsUtils.getHostPortString(hostAddr.get(), port);
        // The proxy is created when it is first used, since the gRPC stubs are not yet available here.
        omProxies.add(OMProxyInfo.newInstance(null, omSvcId, nodeId, rpcAddrStr));
      } else {
        LOG.error("expected host address not defined for: {}", rpcAddrKey);
        throw new ConfigurationException(rpcAddrKey + "is not defined");
      }
    }

    if (omProxies.isEmpty()) {
      throw new IllegalArgumentException("Could not find any configured " +
          "addresses for OM. Please configure the system with "
          + OZONE_OM_ADDRESS_KEY);
    }
    Collections.shuffle(omProxies);
    return omProxies;
  }

  /**
   * Get the proxy object which should be used until the next failover event
   * occurs. RPC proxy object is initialized lazily.
   * @return the OM proxy object to invoke methods upon
   */
  @Override
  public OMProxyInfo<OzoneManagerProtocolPB> getProxy() {
    return createOMProxyIfNeeded(getCurrentProxyOMNodeId());
  }

  protected OMProxyInfo<OzoneManagerProtocolPB> createOMProxyIfNeeded(String nodeId) {
    final OMProxyInfo<OzoneManagerProtocolPB> omProxyInfo = getOMProxyMap().get(nodeId);
    omProxyInfo.createProxyIfNeeded(address -> new GrpcOMProxy(stubs.apply(omProxyInfo.getAddressString())));
    return omProxyInfo;
  }

  @Override
  protected synchronized boolean shouldFailover(Exception ex) {
    final Throwable cause = ex.getCause();
    if (cause instanceof StatusRuntimeException) {
      StatusRuntimeException srexp = (StatusRuntimeException) cause;
      Status status = srexp.getStatus();
      if (status.getCode() == Status.Code.RESOURCE_EXHAUSTED) {
        LOG.debug("Grpc response has invalid length, {}", srexp.getMessage());
        return false;
      } else if (status.getCode() == Status.Code.DATA_LOSS) {
        LOG.debug("Grpc unrecoverable data loss or corruption, {}",
                srexp.getMessage());
        return false;
      }
    } else if (cause instanceof OMException && ((OMException) cause).getResult() == SSL_CONNECTION_FAILURE) {
      return false;
    }
    return super.shouldFailover(ex);
  }

  @Override
  public synchronized void close() throws IOException { }

  // need to throw if nodeID not in omAddresses
  public String getGrpcProxyAddress(String nodeId) throws IOException {
    final OMProxyInfo<OzoneManagerProtocolPB> p = getOMProxyMap().get(nodeId);
    if (p != null) {
      return p.getAddressString();
    } else {
      LOG.error("expected nodeId not found in omProxies for proxyhost {}",
          nodeId);
      throw new IOException(
          "expected nodeId not found in omProxies for proxyhost");
    }
  }

  /**
   * An {@link OzoneManagerProtocolPB} over the gRPC stub of an OM.
   * A gRPC failure is thrown as a {@link ServiceException},
   * whose cause is the OM exception if the failure comes from OM.
   */
  private static final class GrpcOMProxy implements OzoneManagerProtocolPB, Closeable {
    private final OzoneManagerServiceBlockingStub stub;

    private GrpcOMProxy(OzoneManagerServiceBlockingStub stub) {
      this.stub = stub;
    }

    @Override
    public OMResponse submitRequest(RpcController controller, OMRequest request) throws ServiceException {
      final InetAddress localHost;
      try {
        localHost = InetAddress.getLocalHost();
      } catch (UnknownHostException e) {
        throw new ServiceException(e);
      }
      final Context context = Context.current()
          .withValue(GrpcClientConstants.CLIENT_IP_ADDRESS_CTX_KEY, localHost.getHostAddress())
          .withValue(GrpcClientConstants.CLIENT_HOSTNAME_CTX_KEY, localHost.getHostName());
      final Context previous = context.attach();
      try {
        return stub.submitRequest(request);
      } catch (StatusRuntimeException e) {
        throw toServiceException(e);
      } finally {
        context.detach(previous);
      }
    }

    private static ServiceException toServiceException(StatusRuntimeException e) {
      final OMGrpcError omError = OMGrpcError.fromStatusRuntimeException(e);
      if (omError != null) {
        return new ServiceException(omError.toException());
      }
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE && e.getCause() instanceof SSLHandshakeException) {
        return new ServiceException(new OMException(e, SSL_CONNECTION_FAILURE));
      }
      // a failure generated by gRPC, e.g. a connection failure
      return new ServiceException(e);
    }

    @Override
    public void close() {
      // GrpcOmTransport owns the gRPC channels.
    }
  }
}
