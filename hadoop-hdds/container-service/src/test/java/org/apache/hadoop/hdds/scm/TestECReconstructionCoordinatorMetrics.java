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

package org.apache.hadoop.hdds.scm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeStateMachine;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;
import org.apache.hadoop.ozone.container.ec.reconstruction.ECReconstructionCoordinator;
import org.apache.hadoop.ozone.container.ec.reconstruction.ECReconstructionMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests ContainerClientMetrics lifecycle in ECReconstructionCoordinator.
 */
class TestECReconstructionCoordinatorMetrics {

  @BeforeEach
  @AfterEach
  void resetMetrics() {
    while (ContainerClientMetrics.referenceCount > 0) {
      ContainerClientMetrics.release();
    }
  }

  @Test
  void closeReleasesContainerClientMetrics() throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration();
    DatanodeStateMachine stateMachine = mock(DatanodeStateMachine.class);
    StateContext context = new StateContext(conf,
        DatanodeStateMachine.DatanodeStates.getInitState(), stateMachine, "");
    ECReconstructionMetrics metrics = ECReconstructionMetrics.create();

    try (ECReconstructionCoordinator coordinator =
        new ECReconstructionCoordinator(conf, null, null, context, metrics,
            "")) {
      assertEquals(1, ContainerClientMetrics.referenceCount);
    } finally {
      metrics.unRegister();
    }

    assertEquals(0, ContainerClientMetrics.referenceCount);
  }
}
