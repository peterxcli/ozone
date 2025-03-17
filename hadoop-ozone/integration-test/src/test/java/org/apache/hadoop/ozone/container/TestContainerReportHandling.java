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

package org.apache.hadoop.ozone.container;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor.THREE;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_DEADNODE_INTERVAL;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_STALENODE_INTERVAL;
import static org.apache.hadoop.ozone.container.TestHelper.waitForContainerClose;
import static org.apache.hadoop.ozone.container.TestHelper.waitForContainerStateInSCM;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerManager;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.MiniOzoneHAClusterImpl;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for container report handling in both non-HA and HA configurations.
 */
public class TestContainerReportHandling {
  private static final String VOLUME = "vol1";
  private static final String BUCKET = "bucket1";
  private static final String KEY = "key1";

  /**
   * Tests that a DELETING (or DELETED) container moves to the CLOSED state if a non-empty replica is reported.
   * To do this, the test first creates a key and closes its corresponding container. Then it moves that container to
   * DELETING (or DELETED) state using ContainerManager. Then it restarts a Datanode hosting that container,
   * making it send a full container report.
   * Finally, the test waits for the container to move from DELETING (or DELETED) to CLOSED.
   */
  @ParameterizedTest
  @EnumSource(value = HddsProtos.LifeCycleState.class,
      names = {"DELETING", "DELETED"})
  void testDeletingOrDeletedContainerTransitionsToClosedWhenNonEmptyReplicaIsReported(
      HddsProtos.LifeCycleState desiredState)
      throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setTimeDuration(OZONE_SCM_STALENODE_INTERVAL, 3, TimeUnit.SECONDS);
    conf.setTimeDuration(OZONE_SCM_DEADNODE_INTERVAL, 6, TimeUnit.SECONDS);

    Path clusterPath = null;
    try (MiniOzoneCluster cluster = newCluster(conf)) {
      cluster.waitForClusterToBeReady();
      clusterPath = Paths.get(cluster.getBaseDir());

      try (OzoneClient client = cluster.newClient()) {
        // create a container and close it
        createTestData(client);
        List<OmKeyLocationInfo> keyLocations = lookupKey(cluster);
        assertThat(keyLocations).isNotEmpty();
        OmKeyLocationInfo keyLocation = keyLocations.get(0);
        ContainerID containerID = ContainerID.valueOf(keyLocation.getContainerID());
        waitForContainerClose(cluster, containerID.getId());

        // also wait till the container is closed in SCM
        waitForContainerStateInSCM(cluster.getStorageContainerManager(), containerID, HddsProtos.LifeCycleState.CLOSED);

        // move the container to desired state (DELETING or DELETED)
        ContainerManager containerManager = cluster.getStorageContainerManager().getContainerManager();
        if (desiredState == HddsProtos.LifeCycleState.DELETING) {
          containerManager.updateContainerState(containerID, HddsProtos.LifeCycleEvent.DELETE);
        } else {
          containerManager.updateContainerState(containerID, HddsProtos.LifeCycleEvent.DELETE);
          containerManager.updateContainerState(containerID, HddsProtos.LifeCycleEvent.CLEANUP);
        }
        waitForContainerStateInSCM(cluster.getStorageContainerManager(), containerID, desiredState);

        // restart a datanode hosting the container
        HddsDatanodeService datanode = cluster.getHddsDatanodes().get(0);
        cluster.restartHddsDatanode(datanode.getDatanodeDetails(), true);

        // wait for the container to move back to CLOSED state
        waitForContainerStateInSCM(cluster.getStorageContainerManager(), containerID, HddsProtos.LifeCycleState.CLOSED);
      }
    } finally {
      if (clusterPath != null) {
        FileUtil.fullyDelete(clusterPath.toFile());
      }
    }
  }

  /**
   * Tests that a DELETING (or DELETED) container moves to the CLOSED state if a non-empty replica is reported
   * in an HA environment with multiple SCMs.
   * To do this, the test first creates a key and closes its corresponding container. Then it moves that container to
   * DELETING (or DELETED) state using ContainerManager. Then it restarts a Datanode hosting that container,
   * making it send a full container report.
   * Finally, the test waits for the container to move from DELETING (or DELETED) to CLOSED in all SCMs.
   */
  @ParameterizedTest
  @EnumSource(value = HddsProtos.LifeCycleState.class,
      names = {"DELETING", "DELETED"})
  void testDeletingOrDeletedContainerTransitionsToClosedWhenNonEmptyReplicaIsReportedWithScmHA(
      HddsProtos.LifeCycleState desiredState)
      throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setTimeDuration(OZONE_SCM_STALENODE_INTERVAL, 3, TimeUnit.SECONDS);
    conf.setTimeDuration(OZONE_SCM_DEADNODE_INTERVAL, 6, TimeUnit.SECONDS);

    int numSCM = 3;
    Path clusterPath = null;
    try (MiniOzoneHAClusterImpl cluster = newHACluster(conf, numSCM)) {
      cluster.waitForClusterToBeReady();
      clusterPath = Paths.get(cluster.getBaseDir());

      try (OzoneClient client = cluster.newClient()) {
        // create a container and close it
        createTestData(client);
        List<OmKeyLocationInfo> keyLocations = lookupKey(cluster);
        assertThat(keyLocations).isNotEmpty();
        OmKeyLocationInfo keyLocation = keyLocations.get(0);
        ContainerID containerID = ContainerID.valueOf(keyLocation.getContainerID());
        waitForContainerClose(cluster, containerID.getId());

        waitForContainerStateInAllSCMs(cluster, containerID, HddsProtos.LifeCycleState.CLOSED);

        // move the container to desired state (DELETING or DELETED) in all SCMs
        for (StorageContainerManager scm : cluster.getStorageContainerManagers()) {
          ContainerManager containerManager = scm.getContainerManager();
          if (desiredState == HddsProtos.LifeCycleState.DELETING) {
            containerManager.updateContainerState(containerID, HddsProtos.LifeCycleEvent.DELETE);
          } else {
            containerManager.updateContainerState(containerID, HddsProtos.LifeCycleEvent.DELETE);
            containerManager.updateContainerState(containerID, HddsProtos.LifeCycleEvent.CLEANUP);
          }
        }
        waitForContainerStateInAllSCMs(cluster, containerID, desiredState);

        // restart a datanode hosting the container
        HddsDatanodeService datanode = cluster.getHddsDatanodes().get(0);
        cluster.restartHddsDatanode(datanode.getDatanodeDetails(), true);

        // wait for the container to move back to CLOSED state in all SCMs
        waitForContainerStateInAllSCMs(cluster, containerID, HddsProtos.LifeCycleState.CLOSED);
      }
    } finally {
      if (clusterPath != null) {
        FileUtil.fullyDelete(clusterPath.toFile());
      }
    }
  }

  private static MiniOzoneCluster newCluster(OzoneConfiguration conf)
      throws IOException {
    return MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(3)
        .build();
  }

  private static MiniOzoneHAClusterImpl newHACluster(OzoneConfiguration conf, int numSCM) throws IOException {
    return (MiniOzoneHAClusterImpl) MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(3)
        .build();
  }

  private static List<OmKeyLocationInfo> lookupKey(MiniOzoneCluster cluster)
      throws IOException {
    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(VOLUME)
        .setBucketName(BUCKET)
        .setKeyName(KEY)
        .build();
    OmKeyInfo keyInfo = cluster.getOzoneManager().lookupKey(keyArgs);
    List<OmKeyLocationInfoGroup> groups = keyInfo.getKeyLocationVersions();
    assertThat(groups).isNotEmpty();
    return groups.get(0).getLocationList();
  }

  private void createTestData(OzoneClient client) throws IOException {
    ObjectStore objectStore = client.getObjectStore();
    objectStore.createVolume(VOLUME);
    OzoneVolume volume = objectStore.getVolume(VOLUME);
    volume.createBucket(BUCKET);
    OzoneBucket bucket = volume.getBucket(BUCKET);
    try (OutputStream out = bucket.createKey(KEY, 0, RatisReplicationConfig.getInstance(THREE), emptyMap())) {
      out.write("test".getBytes(UTF_8));
    }
  }

  private static void waitForContainerStateInAllSCMs(MiniOzoneHAClusterImpl cluster, ContainerID containerID,
      HddsProtos.LifeCycleState desiredState)
      throws TimeoutException, InterruptedException {
    for (StorageContainerManager scm : cluster.getStorageContainerManagers()) {
      waitForContainerStateInSCM(scm, containerID, desiredState);
    }
  }
}
