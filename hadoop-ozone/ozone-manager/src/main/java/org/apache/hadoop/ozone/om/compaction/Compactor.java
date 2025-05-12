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

package org.apache.hadoop.ozone.om.compaction;

import java.util.List;
import org.apache.hadoop.hdds.utils.db.KeyRange;

/**
 * Interface for compactors that handle RocksDB compaction.
 */
public interface Compactor extends Runnable {
  /**
   * Get the table this compactor is responsible for.
   */
  String getTableName();

  /**
   * Check if a range needs compaction based on its statistics.
   */
  default boolean needsCompaction(KeyRangeStats stats, int minTombstones, double tombstonePercentage) {
    if (stats.getNumDeletion() < minTombstones) {
      return false;
    }
    return stats.getTombstonePercentage() >= tombstonePercentage;
  }

  /**
   * Compact a specific range.
   */
  void compactRange(KeyRange range);

  /**
   * Get the ranges that need compaction.
   */
  List<KeyRange> getRangesNeedingCompaction();
}
