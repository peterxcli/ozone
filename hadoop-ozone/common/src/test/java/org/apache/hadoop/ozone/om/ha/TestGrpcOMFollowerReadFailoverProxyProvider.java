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

import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_GRPC_PORT_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_NODES_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ServiceException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.ha.ConfUtils;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMLeaderNotReadyException;
import org.apache.hadoop.ozone.om.exceptions.OMNotLeaderException;
import org.apache.hadoop.ozone.om.helpers.ReadConsistency;
import org.apache.hadoop.ozone.om.protocolPB.grpc.OMGrpcError;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerServiceGrpc;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerServiceGrpc.OzoneManagerServiceBlockingStub;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.ReadException;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link GrpcOMFollowerReadFailoverProxyProvider}.
 */
public class TestGrpcOMFollowerReadFailoverProxyProvider {
  private static final String OM_SERVICE_ID = "om-service-test1";

  private GrpcOMFollowerReadFailoverProxyProvider provider;
  /** The OM nodes in the order used for follower read. */
  private List<String> nodeIds;
  /** The leader OM, which is the last node used for follower read. */
  private String leaderNodeId;

  /** The failure that an OM node returns for its next request. An OM node without a failure serves the request. */
  private final Map<String, StatusRuntimeException> failures = new HashMap<>();
  /** The requests sent to each OM node. */
  private final Map<String, List<OMRequest>> nodeRequests = new HashMap<>();
  private final List<Server> servers = new ArrayList<>();
  private final List<ManagedChannel> channels = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    final OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(ConfUtils.addKeySuffixes(OZONE_OM_NODES_KEY, OM_SERVICE_ID), "om1,om2,om3");
    for (int i = 1; i <= 3; i++) {
      conf.set(ConfUtils.addKeySuffixes(OZONE_OM_ADDRESS_KEY, OM_SERVICE_ID, "om" + i), "localhost");
      conf.setInt(ConfUtils.addKeySuffixes(OZONE_OM_GRPC_PORT_KEY, OM_SERVICE_ID, "om" + i), 19880 + i);
    }
    final Map<String, OzoneManagerServiceBlockingStub> stubs = new HashMap<>();
    final GrpcOMFailoverProxyProvider leaderProxy = new GrpcOMFailoverProxyProvider(
        conf, UserGroupInformation.getCurrentUser(), OM_SERVICE_ID, stubs::get);
    nodeIds = new ArrayList<>(leaderProxy.getOMProxyMap().getNodeIds());
    for (String nodeId : nodeIds) {
      nodeRequests.put(nodeId, new ArrayList<>());
      stubs.put(leaderProxy.getGrpcProxyAddress(nodeId), OzoneManagerServiceGrpc.newBlockingStub(startOM(nodeId)));
    }
    leaderNodeId = nodeIds.get(2);
    leaderProxy.setNextOmProxy(leaderNodeId);
    leaderProxy.performFailover(null);
    provider = new GrpcOMFollowerReadFailoverProxyProvider(leaderProxy,
        ReadConsistency.LINEARIZABLE_ALLOW_FOLLOWER, ReadConsistency.DEFAULT, true);
  }

  @AfterEach
  void tearDown() {
    channels.forEach(ManagedChannel::shutdownNow);
    servers.forEach(Server::shutdownNow);
  }

  @Test
  void testReadOnCurrentNode() throws Exception {
    submit(newRequest(Type.ListVolume));

    assertEquals(1, nodeRequests.get(nodeIds.get(0)).size());
    assertEquals(ReadConsistency.LINEARIZABLE_ALLOW_FOLLOWER.getHint(),
        nodeRequests.get(nodeIds.get(0)).get(0).getReadConsistencyHint());
    assertEquals(0, nodeRequests.get(leaderNodeId).size());
  }

  @Test
  void testWriteOnLeader() throws Exception {
    submit(newRequest(Type.CreateVolume));

    assertEquals(1, countNodeRequests());
    assertEquals(1, nodeRequests.get(leaderNodeId).size());
    assertEquals(ReadConsistency.DEFAULT.getHint(), nodeRequests.get(leaderNodeId).get(0).getReadConsistencyHint());
  }

  @ParameterizedTest
  @MethodSource("failuresToTryNextNode")
  void testFailureTriesNextNode(StatusRuntimeException failure) throws Exception {
    failures.put(nodeIds.get(0), failure);

    submit(newRequest(Type.ListVolume));

    assertEquals(1, nodeRequests.get(nodeIds.get(0)).size());
    assertEquals(1, nodeRequests.get(nodeIds.get(1)).size());
    assertEquals(0, nodeRequests.get(leaderNodeId).size());
    assertEquals(nodeIds.get(1), provider.getCurrentFollowerReadNodeId());
  }

  static Stream<StatusRuntimeException> failuresToTryNextNode() {
    return Stream.of(
        Status.UNAVAILABLE.asRuntimeException(),
        toServerError(new ReadIndexException("read index failed")),
        toServerError(new ReadException("read failed")));
  }

  @Test
  void testFallBackToLeaderWhenAllNodesFail() throws Exception {
    for (String nodeId : nodeIds) {
      failures.put(nodeId, Status.UNAVAILABLE.asRuntimeException());
    }

    submit(newRequest(Type.ListVolume));

    for (String nodeId : nodeIds) {
      assertEquals(ReadConsistency.LINEARIZABLE_ALLOW_FOLLOWER.getHint(),
          nodeRequests.get(nodeId).get(0).getReadConsistencyHint());
    }
    assertEquals(nodeIds.size() + 1, countNodeRequests());
    // The leader gets the leader read consistency, not the follower read consistency.
    assertEquals(ReadConsistency.DEFAULT.getHint(), nodeRequests.get(leaderNodeId).get(1).getReadConsistencyHint());
    assertTrue(provider.isOmServiceSupportsFollowerRead());
  }

  @Test
  void testNotLeaderExceptionDisablesFollowerRead() throws Exception {
    failures.put(nodeIds.get(0), toServerError(new OMNotLeaderException(RaftPeerId.valueOf(nodeIds.get(0)))));

    submit(newRequest(Type.ListVolume));

    assertFalse(provider.isOmServiceSupportsFollowerRead());
    assertEquals(1, nodeRequests.get(nodeIds.get(0)).size());
    assertEquals(1, nodeRequests.get(leaderNodeId).size());

    // The next read goes to the leader directly.
    submit(newRequest(Type.ListVolume));
    assertEquals(1, nodeRequests.get(nodeIds.get(0)).size());
    assertEquals(2, nodeRequests.get(leaderNodeId).size());
  }

  @Test
  void testLeaderNotReadyExceptionIsThrown() {
    failures.put(nodeIds.get(0), toServerError(new OMLeaderNotReadyException("leader is not ready")));

    final ServiceException e = assertThrows(ServiceException.class, () -> submit(newRequest(Type.ListVolume)));
    assertInstanceOf(OMLeaderNotReadyException.class, e.getCause());
    assertEquals(1, countNodeRequests());
    assertEquals(nodeIds.get(0), provider.getCurrentFollowerReadNodeId());
    assertTrue(provider.isOmServiceSupportsFollowerRead());
  }

  @Test
  void testFailureWithoutFailoverIsThrown() {
    failures.put(nodeIds.get(0), Status.RESOURCE_EXHAUSTED.asRuntimeException());

    final ServiceException e = assertThrows(ServiceException.class, () -> submit(newRequest(Type.ListVolume)));
    assertEquals(Status.Code.RESOURCE_EXHAUSTED, assertInstanceOf(StatusRuntimeException.class, e.getCause())
        .getStatus().getCode());
    assertEquals(1, countNodeRequests());
    assertEquals(nodeIds.get(0), provider.getCurrentFollowerReadNodeId());
  }

  @Test
  void testSslHandshakeFailureIsThrown() {
    failures.put(nodeIds.get(0), Status.UNAVAILABLE.withCause(new SSLHandshakeException("test")).asRuntimeException());

    final ServiceException e = assertThrows(ServiceException.class, () -> submit(newRequest(Type.ListVolume)));
    assertEquals(OMException.ResultCodes.SSL_CONNECTION_FAILURE,
        assertInstanceOf(OMException.class, e.getCause()).getResult());
    assertEquals(1, countNodeRequests());
  }

  private void submit(OMRequest request) throws ServiceException {
    provider.getProxy().proxy.submitRequest(null, request);
  }

  /** Start an OM node in process. */
  private ManagedChannel startOM(String nodeId) throws IOException {
    final String name = InProcessServerBuilder.generateName();
    servers.add(InProcessServerBuilder.forName(name)
        .directExecutor()
        .addService(new OzoneManagerServiceGrpc.OzoneManagerServiceImplBase() {
          @Override
          public void submitRequest(OMRequest request, StreamObserver<OMResponse> responseObserver) {
            nodeRequests.get(nodeId).add(request);
            final StatusRuntimeException failure = failures.remove(nodeId);
            if (failure != null) {
              responseObserver.onError(failure);
              return;
            }
            responseObserver.onNext(newResponse(request));
            responseObserver.onCompleted();
          }
        })
        .build()
        .start());
    final ManagedChannel channel = InProcessChannelBuilder.forName(name)
        .directExecutor()
        .propagateCauseWithStatus(true)
        .build();
    channels.add(channel);
    return channel;
  }

  private int countNodeRequests() {
    return nodeRequests.values().stream().mapToInt(List::size).sum();
  }

  /** The error that the OM gRPC server returns for an exception, see OzoneManagerServiceGrpc. */
  private static StatusRuntimeException toServerError(IOException e) {
    return OMGrpcError.toStatusRuntimeException(e);
  }

  private static OMRequest newRequest(Type cmdType) {
    return OMRequest.newBuilder()
        .setCmdType(cmdType)
        .setClientId("test")
        .build();
  }

  private static OMResponse newResponse(OMRequest request) {
    return OMResponse.newBuilder()
        .setCmdType(request.getCmdType())
        .setStatus(org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status.OK)
        .build();
  }
}
