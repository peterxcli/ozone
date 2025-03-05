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

package org.apache.hadoop.ozone.debug.datanode.container;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.SecretKeyProtocol;
import org.apache.hadoop.hdds.protocolPB.SCMSecurityProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdds.security.SecurityConfig;
import org.apache.hadoop.hdds.security.symmetric.DefaultSecretKeyClient;
import org.apache.hadoop.hdds.security.symmetric.SecretKeyClient;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.security.x509.certificate.client.DNCertificateClient;
import org.apache.hadoop.hdds.utils.HddsServerUtil;
import org.apache.hadoop.ozone.OzoneSecurityUtil;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.replication.ContainerReplicationSource;
import org.apache.hadoop.ozone.container.replication.ContainerUploader;
import org.apache.hadoop.ozone.container.replication.CopyContainerCompression;
import org.apache.hadoop.ozone.container.replication.GrpcContainerUploader;
import org.apache.hadoop.ozone.container.replication.OnDemandContainerReplicationSource;
import org.apache.hadoop.ozone.protocol.commands.ReplicateContainerCommand;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Handles {@code ozone debug datanode container replicate} command.
 * This command triggers push replication of one or more containers to specified
 * datanodes.
 */
@Command(name = "replicate", description = "Trigger push replication of container(s) to specified datanode(s)")
public class ReplicateSubcommand implements Callable<Void> {

  private static final Logger LOG = LoggerFactory.getLogger(ReplicateSubcommand.class);

  @CommandLine.ParentCommand
  private ContainerCommands parent;

  @CommandLine.Option(names = {
      "--container" }, required = true, description = "Container ID(s) to replicate (comma-separated)")
  private String containerIds;

  @CommandLine.Option(names = {
      "--target" }, required = true, description = "Target datanode UUID(s) (comma-separated)")
  private String targetDatanodeIds;

  private OzoneConfiguration conf;
  private SecurityConfig secConf;
  private DatanodeDetails datanodeDetails;

  @Override
  public Void call() throws Exception {
    parent.loadContainersFromVolumes();
    conf = parent.getConf();
    datanodeDetails = parent.readDatanodeDetails();

    CopyContainerCompression compression = CopyContainerCompression.getConf(conf);

    LOG.info("Starting replication of containers {} to {} using {}",
        containerIds, targetDatanodeIds, compression);

    // Parse container IDs
    String[] containerIdStrings = containerIds.split(",");
    List<Long> containerIdList = new ArrayList<>();
    for (String containerIdStr : containerIdStrings) {
      try {
        long containerId = Long.parseLong(containerIdStr.trim());
        containerIdList.add(containerId);
      } catch (NumberFormatException e) {
        LOG.error("Invalid container ID: {}", containerIdStr);
        throw new IllegalArgumentException("Invalid container ID: " + containerIdStr);
      }
    }

    // Parse target datanode IDs
    String[] targetDatanodeIdStrings = targetDatanodeIds.split(",");
    List<DatanodeDetails> targetDatanodeList = new ArrayList<>();
    for (String targetDatanodeId : targetDatanodeIdStrings) {
      try {
        DatanodeDetails target = DatanodeDetails.newBuilder()
            .setUuid(UUID.fromString(targetDatanodeId.trim()))
            .build();
        targetDatanodeList.add(target);
      } catch (Exception e) {
        LOG.error("Invalid datanode UUID: {}", targetDatanodeId, e);
        throw new IllegalArgumentException("Invalid datanode UUID: " + targetDatanodeId);
      }
    }

    CertificateClient certClient = null;
    if (OzoneSecurityUtil.isSecurityEnabled(conf)) {
      certClient = initializeCertificateClient();

      if (secConf.isTokenEnabled()) {
        SecretKeyProtocol secretKeyProtocol = HddsServerUtil.getSecretKeyClientForDatanode(conf);
        SecretKeyClient secretKeyClient = DefaultSecretKeyClient.create(
            conf, secretKeyProtocol, "");
        secretKeyClient.start(conf);
      }
    }

    // ContainerReplicator pushReplicator = new PushReplicator(parent.getConf(),
    //     new OnDemandContainerReplicationSource(parent.getController()),
    //     new GrpcContainerUploader(parent.getConf(), certClient));
    ContainerReplicationSource source = new OnDemandContainerReplicationSource(parent.getController());
    ContainerUploader uploader = new GrpcContainerUploader(parent.getConf(), certClient);

    // Process each container
    for (Long containerId : containerIdList) {
      Container container = parent.getController().getContainer(containerId);
      if (container == null) {
        LOG.error("Container {} not found", containerId);
        System.out.println("Container " + containerId + " not found");
        continue;
      }

      ContainerData containerData = container.getContainerData();
      System.out.println("Processing container: " + containerId);

      // Replicate to each target datanode
      for (DatanodeDetails targetDatanode : targetDatanodeList) {
        try {
          System.out.println("Replicating container " + containerId +
              " to datanode " + targetDatanode.getUuidString());
          // Get the container replication source and perform replication
          // This is a simplified version - in a real implementation, you would
          // need to set up the proper ContainerReplicator with all dependencies
          System.out.println("Replication initiated for container " + containerId +
              " to datanode " + targetDatanode.getUuidString());

          LOG.info("Replicating container {} to datanode {}", containerId, targetDatanode.getUuidString());
          CompletableFuture<Void> fut = new CompletableFuture<>();
          CountingOutputStream output = new CountingOutputStream(
              uploader.startUpload(containerId, targetDatanode, fut, compression));
          source.copyData(containerId, output, compression);
          fut.get();
          LOG.info("Replication completed for container {}, written {} bytes", containerId, output.getByteCount());
        } catch (Exception e) {
          LOG.error("Failed to replicate container {} to datanode {}",
              containerId, targetDatanode.getUuidString(), e);
          System.out.println("Failed to replicate container " + containerId +
              " to datanode " + targetDatanode.getUuidString() +
              ": " + e.getMessage());
        }
      }
    }

    return null;
  }

  SCMSecurityProtocolClientSideTranslatorPB createScmSecurityClient()
      throws IOException {
    return HddsServerUtil.getScmSecurityClientWithMaxRetry(conf, UserGroupInformation.getCurrentUser());
  }

  public CertificateClient initializeCertificateClient() throws IOException {
    LOG.info("Initializing secure Datanode.");

    DNCertificateClient dnCertClient = new DNCertificateClient(secConf,
        createScmSecurityClient(),
        datanodeDetails,
        datanodeDetails.getCertSerialId(), this::saveNewCertId,
        this::terminateDatanode);
    dnCertClient.initWithRecovery();
    return dnCertClient;
  }

  public void saveNewCertId(String newCertId) {
  }

  public void terminateDatanode() {
  }
}
