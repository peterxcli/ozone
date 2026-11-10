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

import static org.apache.hadoop.ozone.om.ha.OMFailoverProxyProviderBase.getLeaderNotReadyException;
import static org.apache.hadoop.ozone.om.ha.OMFailoverProxyProviderBase.getNotLeaderException;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import java.io.IOException;
import java.util.stream.Collectors;
import org.apache.hadoop.io.retry.FailoverProxyProvider;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.ozone.om.helpers.ReadConsistency;
import org.apache.hadoop.ozone.om.protocolPB.GrpcOmTransport;
import org.apache.hadoop.ozone.om.protocolPB.OzoneManagerProtocolPB;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follower read support for {@link GrpcOmTransport}.
 * <p>
 * If follower read is enabled, a read request is sent to the current follower read OM node,
 * which can be either a leader or a follower. If the OM node fails, the request is sent
 * to the next OM node. If all the OM nodes have failed, the request falls back to the leader OM.
 * <p>
 * Write requests, and read requests when follower read is disabled, are sent to the leader OM.
 * A failure from the leader OM, or a failure that needs the OM retry policy, such as
 * {@link org.apache.hadoop.ozone.om.exceptions.OMLeaderNotReadyException}, is handled by
 * {@link OMFailoverProxyProviderBase#getRetryPolicy(int)}.
 */
public class GrpcOMFollowerReadFailoverProxyProvider extends FollowerReadFailoverProxyProviderBase
    implements FailoverProxyProvider<OzoneManagerProtocolPB> {
  private static final Logger LOG = LoggerFactory.getLogger(GrpcOMFollowerReadFailoverProxyProvider.class);

  /** The inner proxy provider used for leader-based failover. */
  private final GrpcOMFailoverProxyProvider leaderProxy;

  /** The combined proxy which sends a request to a follower or the leader. */
  private final ProxyInfo<OzoneManagerProtocolPB> combinedProxy;

  public GrpcOMFollowerReadFailoverProxyProvider(GrpcOMFailoverProxyProvider leaderProxy,
      ReadConsistency followerReadConsistencyType, ReadConsistency leaderReadConsistencyType,
      boolean defaultFollowerReadEnabled) {
    super(followerReadConsistencyType, leaderReadConsistencyType, defaultFollowerReadEnabled);
    this.leaderProxy = leaderProxy;
    final String combinedInfo = leaderProxy.getOMProxies().stream()
        .map(p -> p.proxyInfo)
        .collect(Collectors.joining(", ", "[", "]"));
    this.combinedProxy = new ProxyInfo<>(new FollowerReadProxy(), combinedInfo);
  }

  @Override
  protected GrpcOMFailoverProxyProvider getLeaderProxy() {
    return leaderProxy;
  }

  @Override
  public Class<OzoneManagerProtocolPB> getInterface() {
    return OzoneManagerProtocolPB.class;
  }

  @Override
  public ProxyInfo<OzoneManagerProtocolPB> getProxy() {
    return combinedProxy;
  }

  @Override
  public void performFailover(OzoneManagerProtocolPB currentProxy) {
    leaderProxy.performFailover(currentProxy);
  }

  public RetryPolicy getRetryPolicy(int maxFailovers) {
    return leaderProxy.getRetryPolicy(maxFailovers);
  }

  @Override
  public void close() throws IOException {
    leaderProxy.close();
  }

  /** Send a request to an OM follower if follower read is used, otherwise to the leader OM. */
  private class FollowerReadProxy implements OzoneManagerProtocolPB {
    @Override
    public OMResponse submitRequest(RpcController controller, OMRequest request) throws ServiceException {
      if (shouldUseFollowerRead(request)) {
        final OMRequest followerRequest = addReadConsistencyHint(request, true);
        final ReadConsistency readConsistency = getReadConsistency(followerRequest);
        int failedCount = 0;
        for (int i = 0; i < getOMNodeCount(); i++) {
          final String nodeId = selectFollowerReadNodeId(readConsistency);
          if (nodeId == null) {
            break;
          }
          final OMProxyInfo<OzoneManagerProtocolPB> current = leaderProxy.createOMProxyIfNeeded(nodeId);
          try {
            final OMResponse response = current.getProxy().submitRequest(controller, followerRequest);
            LOG.debug("Invocation with cmdType {} using follower read proxy {} was successful",
                followerRequest.getCmdType(), current.proxyInfo);
            return response;
          } catch (ServiceException e) {
            LOG.debug("Invocation with cmdType {} using follower read proxy {} failed",
                followerRequest.getCmdType(), current.proxyInfo, e);
            if (getNotLeaderException(e) != null) {
              LOG.debug("Encountered OMNotLeaderException from {}. "
                  + "Disable OM follower read and retry OM leader directly.", current.proxyInfo);
              disableFollowerRead();
              break;
            }
            if (getLeaderNotReadyException(e) != null || !leaderProxy.shouldFailover(e)) {
              throw e;
            }
            failedCount++;
            changeFollowerReadNodeId(nodeId);
          }
        }
        if (failedCount > 0) {
          LOG.warn("{} nodes have failed for read request with cmdType {}. Falling back to leader.",
              failedCount, request.getCmdType());
        }
      }

      // Either follower read is not used, or no OM node has served the request.
      return leaderProxy.getProxy().getProxy().submitRequest(controller, addReadConsistencyHint(request, false));
    }
  }
}
