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

package org.apache.hadoop.ozone.freon.containergenerator;

import com.codahale.metrics.Timer;
import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChecksumData;
import org.apache.hadoop.hdds.scm.OzoneClientConfig;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.hdds.utils.HddsServerUtil;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.common.Checksum;
import org.apache.hadoop.ozone.common.InconsistentStorageStateException;
import org.apache.hadoop.ozone.container.common.helpers.BlockData;
import org.apache.hadoop.ozone.container.common.helpers.ChunkInfo;
import org.apache.hadoop.ozone.container.common.helpers.DatanodeVersionFile;
import org.apache.hadoop.ozone.container.common.impl.ContainerLayoutVersion;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext.WriteChunkStage;
import org.apache.hadoop.ozone.container.common.utils.StorageVolumeUtil;
import org.apache.hadoop.ozone.container.common.volume.MutableVolumeSet;
import org.apache.hadoop.ozone.container.common.volume.RoundRobinVolumeChoosingPolicy;
import org.apache.hadoop.ozone.container.common.volume.StorageVolume;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainer;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.impl.BlockManagerImpl;
import org.apache.hadoop.ozone.container.keyvalue.impl.ChunkManagerFactory;
import org.apache.hadoop.ozone.container.keyvalue.interfaces.ChunkManager;
import org.apache.hadoop.ozone.freon.FreonSubcommand;
import org.kohsuke.MetaInfServices;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Container generator for datanode metadata/data.
 */
@Command(name = "cgdn",
    description = "Offline container metadata generator for Ozone Datanodes.",
    optionListHeading =
        "\nExecute this command with different parameters for each datanodes. "
            + "For example if you have 10 datanodes, use "
            + "'ozone freon cgdn --index=1 --datanodes=10', 'ozone freon"
            + " cgdn --index=2 --datanodes=10', 'ozone freon cgdn "
            + "--index=3 --datanodes=10', ...\n\n",
    versionProvider = HddsVersionProvider.class,
    mixinStandardHelpOptions = true,
    showDefaultValues = true)
@MetaInfServices(FreonSubcommand.class)
@SuppressWarnings("java:S2245") // no need for secure random
public class GeneratorDatanode extends BaseGenerator {

  @Option(names = {"--datanodes"},
      description = "Number of datanodes (to generate only a subset of the "
          + "required containers).",
      defaultValue = "3")
  private int datanodes;

  @Option(names = {"--index"},
      description = "Index of the datanode. For example datanode #3 should "
          + "have only every 3rd container in a 10 node cluster. " +
          "First datanode is 1 not 0.).",
      defaultValue = "1")
  private int datanodeIndex;

  @Option(names = {"--zero"},
      description = "Use zero bytes instead of random data.",
      defaultValue = "false")
  private boolean zero;

  @Option(names = {"--overlap"},
      description = "Number of overlapping pipelines between 1 and 3." +
          " This number provides a lightweight simulation of multi-raft" +
          " placement. Set to 3 to have more sources ((overlap + 1) % 3 = 4)" +
          " for replication in case of node failures.",
      defaultValue = "1")
  private int overlap;

  private ChunkManager chunkManager;
  private BlockManagerImpl blockManager;

  private RoundRobinVolumeChoosingPolicy volumeChoosingPolicy;

  private MutableVolumeSet volumeSet;

  private Checksum checksum;

  private ConfigurationSource config;

  private Timer timer;

  //Simulate ratis log index (incremented for each chunk write)
  private int logCounter;
  private String datanodeId;
  private String scmId;

  @Override
  public Void call() throws Exception {
    init();

    config = createOzoneConfiguration();

    blockManager = new BlockManagerImpl(config);
    chunkManager = ChunkManagerFactory
        .createChunkManager(config, blockManager, null);

    final Collection<String> storageDirs =
        HddsServerUtil.getDatanodeStorageDirs(config);

    String firstStorageDir =
        StorageLocation.parse(storageDirs.iterator().next())
            .getUri().getPath();

    final Path hddsDir = Paths.get(firstStorageDir, "hdds");
    if (!Files.exists(hddsDir)) {
      throw new NoSuchFileException(hddsDir
          + " doesn't exist. Please start a real cluster to initialize the "
          + "VERSION descriptors, and re-start this generator after the files"
          + " are created (but after cluster is stopped).");
    }

    scmId = getScmIdFromStoragePath(hddsDir);

    final File versionFile = new File(firstStorageDir, "hdds/VERSION");
    Properties props = DatanodeVersionFile.readFrom(versionFile);
    if (props.isEmpty()) {
      throw new InconsistentStorageStateException(
          "Version file " + versionFile + " is missing");
    }

    String clusterId = StorageVolumeUtil.getProperty(props,
        OzoneConsts.CLUSTER_ID, versionFile);
    datanodeId = StorageVolumeUtil
        .getProperty(props, OzoneConsts.DATANODE_UUID, versionFile);

    volumeSet = new MutableVolumeSet(datanodeId, clusterId, config, null,
        StorageVolume.VolumeType.DATA_VOLUME, null);

    volumeChoosingPolicy = new RoundRobinVolumeChoosingPolicy();

    final OzoneClientConfig ozoneClientConfig =
        config.getObject(OzoneClientConfig.class);
    checksum = new Checksum(ozoneClientConfig.getChecksumType(),
        ozoneClientConfig.getBytesPerChecksum());

    timer = getMetrics().timer("datanode-generator");
    runTests(this::generateData);
    return null;
  }

  private String getScmIdFromStoragePath(Path hddsDir)
      throws IOException {
    try (Stream<Path> stream = Files.list(hddsDir)) {
      final Optional<Path> scmSpecificDir = stream
          .filter(Files::isDirectory)
          .findFirst();
      if (!scmSpecificDir.isPresent()) {
        throw new NoSuchFileException(
          "SCM specific datanode directory doesn't exist " + hddsDir);
      }
      final Path scmDirName = scmSpecificDir.get().getFileName();
      if (scmDirName == null) {
        throw new IllegalArgumentException(
          "SCM specific datanode directory doesn't exist");
      }
      return scmDirName.toString();
    }
  }


  /**
   * Return the placement of the container with ONE based indexes.
   * (first datanode is 1).
   */
  @VisibleForTesting
  public static Set<Integer> getPlacement(
      long containerId,
      int maxDatanodes,
      int overlap) {
    int parallelPipelines = maxDatanodes / 3;
    int startOffset = (int) ((containerId % parallelPipelines) * 3);

    int pipelineLevel = (int) (containerId / parallelPipelines);

    startOffset += pipelineLevel % overlap;

    Set<Integer> result = new HashSet<>();
    for (int i = startOffset; i < startOffset + 3; i++) {
      result.add(i % maxDatanodes + 1);
    }
    return result;
  }

  public void generateData(long index) throws Exception {

    timer.time((Callable<Void>) () -> {

      long containerId = index;

      Set<Integer> selectedDatanodes = GeneratorDatanode
          .getPlacement(containerId, datanodes, overlap);

      //this container shouldn't be saved to this datanode.
      if (!selectedDatanodes.contains(datanodeIndex)) {
        return null;
      }

      SplittableRandom random = new SplittableRandom(containerId);

      int keyPerContainer = getKeysPerContainer(config);

      final KeyValueContainer container = createContainer(containerId);

      int chunkSize = 4096 * 1024;

      //loop to create multiple blocks per container
      for (long localId = 0; localId < keyPerContainer; localId++) {
        BlockID blockId = new BlockID(containerId, localId);
        BlockData blockData = new BlockData(blockId);

        int chunkIndex = 0;
        int writtenBytes = 0;

        //loop to create multiple chunks per blocks
        while (writtenBytes < getKeySize()) {
          int currentChunkSize =
              Math.min(getKeySize() - writtenBytes, chunkSize);
          String chunkName = "chunk" + chunkIndex++;

          final byte[] data = new byte[currentChunkSize];
          if (!zero) {
            generatedRandomData(random, data);
          }

          ByteBuffer byteBuffer = ByteBuffer.wrap(data);

          //it should be done BEFORE writeChunk consumes the buffer
          final ChecksumData checksumData =
              this.checksum.computeChecksum(byteBuffer).getProtoBufMessage();

          ChunkInfo chunkInfo =
              new ChunkInfo(chunkName, writtenBytes, currentChunkSize);
          writeChunk(container, blockId, chunkInfo, byteBuffer);

          //collect chunk info for putBlock
          blockData.addChunk(ContainerProtos.ChunkInfo.newBuilder()
              .setChunkName(chunkInfo.getChunkName())
              .setLen(chunkInfo.getLen())
              .setOffset(chunkInfo.getOffset())
              .setChecksumData(checksumData)
              .build());

          writtenBytes += currentChunkSize;
        }

        blockManager.persistPutBlock(container, blockData, true);

      }

      container.close();

      return null;
    });

  }

  private void generatedRandomData(SplittableRandom random, byte[] data) {
    int bit = 0;
    int writtenBytes = 0;
    long currentNumber = 0;

    //this section generates one 4 bit long random number and reuse it 4 times
    while (writtenBytes < data.length) {
      if (bit == 0) {
        currentNumber = random.nextLong();
        bit = 3;
      } else {
        bit--;
      }
      data[writtenBytes++] = (byte) currentNumber;
      currentNumber = currentNumber >> 8;
    }
  }

  private KeyValueContainer createContainer(long containerId)
      throws IOException {
    ContainerLayoutVersion layoutVersion =
        ContainerLayoutVersion.getConfiguredVersion(config);
    KeyValueContainerData keyValueContainerData =
        new KeyValueContainerData(containerId, layoutVersion,
            getContainerSize(config),
            getPrefix(), datanodeId);

    KeyValueContainer keyValueContainer =
        new KeyValueContainer(keyValueContainerData, config);

    try {
      keyValueContainer.create(volumeSet, volumeChoosingPolicy, scmId);
    } catch (StorageContainerException ex) {
      throw new RuntimeException(ex);
    }
    return keyValueContainer;
  }

  private void writeChunk(
      KeyValueContainer container, BlockID blockId,
      ChunkInfo chunkInfo, ByteBuffer data
  ) throws IOException {

    DispatcherContext context =
        DispatcherContext
            .newBuilder(DispatcherContext.Op.WRITE_STATE_MACHINE_DATA)
            .setStage(WriteChunkStage.WRITE_DATA)
            .setTerm(1L)
            .setLogIndex(logCounter)
            .build();
    chunkManager
        .writeChunk(container, blockId, chunkInfo,
            data,
            context);

    context =
        DispatcherContext
            .newBuilder(DispatcherContext.Op.APPLY_TRANSACTION)
            .setStage(WriteChunkStage.COMMIT_DATA)
            .setTerm(1L)
            .setLogIndex(logCounter)
            .build();
    chunkManager
        .writeChunk(container, blockId, chunkInfo,
            data,
            context);
    logCounter++;
    chunkManager.finishWriteChunks(container, new BlockData(blockId));
  }

}
