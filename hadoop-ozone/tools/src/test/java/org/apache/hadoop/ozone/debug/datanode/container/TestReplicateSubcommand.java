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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.UUID;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.ozoneimpl.ContainerController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for {@link ReplicateSubcommand}.
 */
public class TestReplicateSubcommand {

  private ReplicateSubcommand subcommand;
  private ContainerCommands parent;
  private ContainerController controller;
  private Container container;
  private ContainerData containerData;

  @BeforeEach
  public void setup() {
    subcommand = new ReplicateSubcommand();
    parent = mock(ContainerCommands.class);
    controller = mock(ContainerController.class);
    container = mock(Container.class);
    containerData = mock(ContainerData.class);

    when(parent.getController()).thenReturn(controller);
    when(container.getContainerData()).thenReturn(containerData);
  }

  private void setParentField() throws Exception {
    Field parentField = ReplicateSubcommand.class.getDeclaredField("parent");
    parentField.setAccessible(true);
    parentField.set(subcommand, parent);
  }

  @Test
  public void testInvalidContainerId() throws Exception {
    // Set up command line arguments
    String[] args = new String[] {
        "--container", "invalid",
        "--target", UUID.randomUUID().toString()
    };

    // Parse command line
    CommandLine cmd = new CommandLine(subcommand);
    cmd.parseArgs(args);
    
    // Set parent
    setParentField();

    // Execute and verify exception
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> subcommand.call());
    assertEquals("Invalid container ID: invalid", exception.getMessage());
  }

  @Test
  public void testInvalidDatanodeUUID() throws Exception {
    // Set up command line arguments
    String[] args = new String[] {
        "--container", "1",
        "--target", "invalid-uuid"
    };

    // Parse command line
    CommandLine cmd = new CommandLine(subcommand);
    cmd.parseArgs(args);
    
    // Set parent
    setParentField();

    // Execute and verify exception
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> subcommand.call());
    assertEquals("Invalid datanode UUID: invalid-uuid", exception.getMessage());
  }

  @Test
  public void testContainerNotFound() throws Exception {
    // Set up command line arguments
    String[] args = new String[] {
        "--container", "1",
        "--target", UUID.randomUUID().toString()
    };

    // Parse command line
    CommandLine cmd = new CommandLine(subcommand);
    cmd.parseArgs(args);
    
    // Set parent
    setParentField();
    
    // Mock container not found
    when(controller.getContainer(1L)).thenReturn(null);

    // Execute
    subcommand.call();
    
    // Verify container was looked up
    verify(controller, times(1)).getContainer(1L);
  }

  @Test
  public void testSuccessfulReplication() throws Exception {
    // Set up command line arguments
    long containerId = 1L;
    UUID targetUuid = UUID.randomUUID();
    String[] args = new String[] {
        "--container", String.valueOf(containerId),
        "--target", targetUuid.toString()
    };

    // Parse command line
    CommandLine cmd = new CommandLine(subcommand);
    cmd.parseArgs(args);
    
    // Set parent
    setParentField();
    
    // Mock container found
    when(controller.getContainer(containerId)).thenReturn(container);

    // Execute
    subcommand.call();
    
    // Verify container was looked up
    verify(controller, times(1)).getContainer(containerId);
    verify(container, times(1)).getContainerData();
  }

  @Test
  public void testMultipleContainersAndTargets() throws Exception {
    // Set up command line arguments
    long containerId1 = 1L;
    long containerId2 = 2L;
    UUID targetUuid1 = UUID.randomUUID();
    UUID targetUuid2 = UUID.randomUUID();
    String[] args = new String[] {
        "--container", containerId1 + "," + containerId2,
        "--target", targetUuid1 + "," + targetUuid2
    };

    // Parse command line
    CommandLine cmd = new CommandLine(subcommand);
    cmd.parseArgs(args);
    
    // Set parent
    setParentField();
    
    // Mock containers found
    when(controller.getContainer(containerId1)).thenReturn(container);
    when(controller.getContainer(containerId2)).thenReturn(container);

    // Execute
    subcommand.call();
    
    // Verify containers were looked up
    verify(controller, times(1)).getContainer(containerId1);
    verify(controller, times(1)).getContainer(containerId2);
    verify(container, times(2)).getContainerData();
  }
}
