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

package org.apache.hadoop.ozone.container.common.volume;

import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.hdds.annotation.InterfaceStability;

/**
 * A closeable handle that manages space allocation on an {@link HddsVolume}.
 * When created, it reserves the requested space in the volume's committed bytes.
 * When closed, it releases the reserved space.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class VolumeSpaceHandle implements AutoCloseable {
  private final HddsVolume volume;
  private final long reservedSpace;
  private boolean closed;

  /**
   * Creates a new VolumeSpaceHandle.
   *
   * @param volume The volume to allocate space from
   * @param reservedSpace The amount of space to reserve
   */
  public VolumeSpaceHandle(HddsVolume volume, long reservedSpace) {
    this.volume = volume;
    this.reservedSpace = reservedSpace;
    this.closed = false;
    volume.incCommittedBytes(reservedSpace);
  }

  /**
   * Gets the volume associated with this handle.
   *
   * @return The HddsVolume
   */
  public HddsVolume getVolume() {
    return volume;
  }

  /**
   * Gets the amount of space reserved by this handle.
   *
   * @return The reserved space in bytes
   */
  public long getReservedSpace() {
    return reservedSpace;
  }

  @Override
  public void close() {
    if (!closed) {
      volume.incCommittedBytes(-reservedSpace);
      closed = true;
    }
  }
} 