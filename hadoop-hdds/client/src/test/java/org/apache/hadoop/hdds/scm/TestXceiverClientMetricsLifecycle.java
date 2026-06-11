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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestXceiverClientMetricsLifecycle {

  @BeforeEach
  @AfterEach
  void resetMetrics() {
    while (XceiverClientMetrics.referenceCount > 0) {
      XceiverClientMetrics.release();
    }
  }

  @Test
  void acquireHandleReleasesOnlyOnce() {
    XceiverClientMetrics.Handle handle =
        XceiverClientMetrics.acquireHandle();

    assertEquals(1, XceiverClientMetrics.referenceCount);

    handle.close();
    assertEquals(0, XceiverClientMetrics.referenceCount);

    handle.close();
    assertEquals(0, XceiverClientMetrics.referenceCount);
  }

  @Test
  void managerCloseReleasesMetricsOnlyWhenLastManagerCloses()
      throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration();
    XceiverClientManager first = null;
    XceiverClientManager second = null;
    boolean firstClosed = false;

    try {
      first = new XceiverClientManager(conf);
      second = new XceiverClientManager(conf);
      XceiverClientMetrics metrics =
          XceiverClientManager.getXceiverClientMetrics();

      assertEquals(2, XceiverClientMetrics.referenceCount);

      first.close();
      firstClosed = true;

      assertEquals(1, XceiverClientMetrics.referenceCount);
      assertSame(metrics, XceiverClientManager.getXceiverClientMetrics());
    } finally {
      if (!firstClosed && first != null) {
        first.close();
      }
      if (second != null) {
        second.close();
      }
    }

    assertEquals(0, XceiverClientMetrics.referenceCount);
  }
}
