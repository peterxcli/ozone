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

package org.apache.hadoop.hdds.scm.storage;

import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.GetCommittedBlockLengthResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.PutBlockResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Type;
import org.apache.hadoop.hdds.scm.XceiverClientFactory;
import org.apache.hadoop.hdds.scm.XceiverClientManager;
import org.apache.hadoop.hdds.scm.XceiverClientRatis;
import org.apache.hadoop.hdds.scm.XceiverClientReply;
import org.apache.hadoop.hdds.scm.pipeline.MockPipeline;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.ratis.client.api.DataStreamApi;
import org.apache.ratis.client.api.DataStreamOutput;
import org.apache.ratis.io.StandardWriteOption;
import org.apache.ratis.io.WriteOption;
import org.apache.ratis.protocol.DataStreamReply;
import org.apache.ratis.protocol.RoutingTable;

/**
 * Mockito-backed datastream pipeline used by {@link BlockDataStreamOutput}
 * tests.
 */
public class MockDataStreamPipeline {

  private final Pipeline pipeline;
  private final XceiverClientRatis xceiverClient;
  private final XceiverClientFactory clientFactory;
  private final BlockID blockID;
  private final DataStreamOutput dataStreamOutput;

  private final List<byte[]> receivedChunks =
      Collections.synchronizedList(new ArrayList<>());
  private final List<ContainerCommandRequestProto> receivedPutBlocks =
      Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger watchForCommitCount = new AtomicInteger();
  private final AtomicLong nextLogIndex = new AtomicLong(1);

  private final Supplier<Throwable> chunkFailure;
  private final int chunkFailAfter;
  private final AtomicInteger chunkCount = new AtomicInteger();

  private final Supplier<Throwable> putBlockFailure;
  private final int putBlockFailAfter;
  private final AtomicInteger putBlockCount = new AtomicInteger();

  private final Supplier<Throwable> watchFailure;
  private final int watchFailAfter;

  public MockDataStreamPipeline() throws IOException {
    this(newBuilder());
  }

  public MockDataStreamPipeline(BlockID blockID) throws IOException {
    this(newBuilder().setBlockID(blockID));
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  private MockDataStreamPipeline(Builder builder) throws IOException {
    this.blockID = builder.blockID;
    this.chunkFailAfter = builder.chunkFailAfter;
    this.chunkFailure = builder.chunkFailure;
    this.putBlockFailAfter = builder.putBlockFailAfter;
    this.putBlockFailure = builder.putBlockFailure;
    this.watchFailAfter = builder.watchFailAfter;
    this.watchFailure = builder.watchFailure;
    this.pipeline = MockPipeline.createRatisPipeline();
    XceiverClientManager.getXceiverClientMetrics();

    this.xceiverClient = mock(XceiverClientRatis.class);
    DataStreamApi dataStreamApi = mock(DataStreamApi.class);
    this.dataStreamOutput = mock(DataStreamOutput.class, CALLS_REAL_METHODS);
    this.clientFactory = mock(XceiverClientFactory.class);

    when(xceiverClient.getPipeline()).thenReturn(pipeline);
    doReturn(0L).when(xceiverClient).getReplicatedMinCommitIndex();
    doReturn(dataStreamApi).when(xceiverClient).getDataStreamApi();
    doReturn(0L).when(xceiverClient)
        .updateCommitInfosMap(any());
    doAnswer(invocation -> {
      ContainerCommandRequestProto request = invocation.getArgument(0);
      CompletableFuture<ContainerCommandResponseProto> response =
          new CompletableFuture<>();
      XceiverClientReply reply = new XceiverClientReply(response);
      if (request.getCmdType() == Type.PutBlock) {
        receivedPutBlocks.add(request);
        int count = putBlockCount.incrementAndGet();
        if (count > putBlockFailAfter && putBlockFailure != null) {
          response.completeExceptionally(putBlockFailure.get());
        } else {
          response.complete(buildPutBlockResponse());
        }
        reply.setLogIndex(nextLogIndex.getAndIncrement());
        return reply;
      }

      response.complete(ContainerCommandResponseProto.newBuilder()
          .setCmdType(request.getCmdType())
          .setResult(Result.SUCCESS)
          .build());
      reply.setLogIndex(0);
      return reply;
    }).when(xceiverClient).sendCommandAsync(any());
    doAnswer(invocation -> {
      long index = invocation.getArgument(0);
      int count = watchForCommitCount.incrementAndGet();
      CompletableFuture<XceiverClientReply> result = new CompletableFuture<>();
      if (count > watchFailAfter && watchFailure != null) {
        result.completeExceptionally(watchFailure.get());
      } else {
        XceiverClientReply reply = new XceiverClientReply(null);
        reply.setLogIndex(index);
        result.complete(reply);
      }
      return result;
    }).when(xceiverClient).watchForCommit(anyLong());

    doReturn(dataStreamOutput).when(dataStreamApi).stream(any(ByteBuffer.class));
    doReturn(dataStreamOutput).when(dataStreamApi)
        .stream(any(ByteBuffer.class), any(RoutingTable.class));
    doAnswer(invocation -> {
      ByteBuffer src = invocation.getArgument(0);
      Iterable<WriteOption> options = invocation.getArgument(1);
      int size = src.remaining();
      for (WriteOption option : options) {
        if (option == StandardWriteOption.CLOSE) {
          if (!receivedChunks.isEmpty()) {
            receivedChunks.remove(receivedChunks.size() - 1);
          }
          src.position(src.limit());
          return CompletableFuture.completedFuture(dataStreamReply(size));
        }
      }

      int count = chunkCount.incrementAndGet();
      if (count > chunkFailAfter && chunkFailure != null) {
        CompletableFuture<DataStreamReply> failed = new CompletableFuture<>();
        failed.completeExceptionally(chunkFailure.get());
        return failed;
      }

      byte[] data = new byte[size];
      src.get(data);
      receivedChunks.add(data);
      return CompletableFuture.completedFuture(dataStreamReply(data.length));
    }).when(dataStreamOutput)
        .writeAsync(any(ByteBuffer.class), any(Iterable.class));

    doReturn(xceiverClient).when(clientFactory)
        .acquireClient(any(Pipeline.class), anyBoolean());
    doReturn(xceiverClient).when(clientFactory)
        .acquireClient(any(Pipeline.class));
  }

  /**
   * Builder for MockDataStreamPipeline.
   */
  public static final class Builder {
    private BlockID blockID = new BlockID(1, 1);
    private Supplier<Throwable> chunkFailure;
    private int chunkFailAfter = Integer.MAX_VALUE;
    private Supplier<Throwable> putBlockFailure;
    private int putBlockFailAfter = Integer.MAX_VALUE;
    private Supplier<Throwable> watchFailure;
    private int watchFailAfter = Integer.MAX_VALUE;

    public Builder setBlockID(BlockID blockID) {
      this.blockID = blockID;
      return this;
    }

    public Builder failChunkAfter(int n, Supplier<Throwable> err) {
      this.chunkFailAfter = n;
      this.chunkFailure = err;
      return this;
    }

    public Builder failPutBlockAfter(int n, Supplier<Throwable> err) {
      this.putBlockFailAfter = n;
      this.putBlockFailure = err;
      return this;
    }

    public Builder failWatchAfter(int n, Supplier<Throwable> err) {
      this.watchFailAfter = n;
      this.watchFailure = err;
      return this;
    }

    public MockDataStreamPipeline build() throws IOException {
      return new MockDataStreamPipeline(this);
    }
  }

  public Pipeline getPipeline() {
    return pipeline;
  }

  public XceiverClientRatis getXceiverClient() {
    return xceiverClient;
  }

  public XceiverClientFactory getClientFactory() {
    return clientFactory;
  }

  public BlockID getBlockID() {
    return blockID;
  }

  public List<byte[]> getReceivedChunks() {
    return receivedChunks;
  }

  public List<ContainerCommandRequestProto> getReceivedPutBlocks() {
    return receivedPutBlocks;
  }

  public int getWatchForCommitCount() {
    return watchForCommitCount.get();
  }

  public byte[] getAllReceivedData() {
    int total = receivedChunks.stream().mapToInt(c -> c.length).sum();
    byte[] result = new byte[total];
    int pos = 0;
    for (byte[] chunk : receivedChunks) {
      System.arraycopy(chunk, 0, result, pos, chunk.length);
      pos += chunk.length;
    }
    return result;
  }

  private ContainerCommandResponseProto buildPutBlockResponse() {
    return ContainerCommandResponseProto.newBuilder()
        .setCmdType(Type.PutBlock)
        .setResult(Result.SUCCESS)
        .setPutBlock(PutBlockResponseProto.newBuilder()
            .setCommittedBlockLength(
                GetCommittedBlockLengthResponseProto.newBuilder()
                    .setBlockID(blockID.getDatanodeBlockIDProtobuf())
                    .setBlockLength(0)
                    .build())
            .build())
        .build();
  }

  private static DataStreamReply dataStreamReply(long bytesWritten) {
    DataStreamReply reply = mock(DataStreamReply.class);
    when(reply.isSuccess()).thenReturn(true);
    when(reply.getBytesWritten()).thenReturn(bytesWritten);
    when(reply.getDataLength()).thenReturn(bytesWritten);
    when(reply.getCommitInfos()).thenReturn(Collections.emptyList());
    return reply;
  }

}
