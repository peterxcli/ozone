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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hdds.utils.BackgroundTask;
import org.apache.hadoop.hdds.utils.BackgroundTaskQueue;
import org.apache.hadoop.hdds.utils.BackgroundTaskResult;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.KeyRange;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.rocksdb.TableProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for compactors that provides common functionality.
 */
public abstract class AbstractCompactor implements Compactor {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractCompactor.class);

  private OMMetadataManager metadataManager;
  private DBStore dbStore;
  private String tableName;
  private BackgroundTaskQueue compactRangeQueue;

  private long maxCompactionEntries;
  private int minTombstones;
  private double tombstoneRatio;
  private int rangesPerRun;

  protected AbstractCompactor(CompactorBuilder builder) {
    this.tableName = builder.getTableName();
    this.maxCompactionEntries = builder.getMaxCompactionEntries();
    this.minTombstones = builder.getMinTombstones();
    this.rangesPerRun = builder.getRangesPerRun();
    this.tombstoneRatio = builder.getTombstoneRatio();
    this.metadataManager = builder.getMetadataManager();
    this.dbStore = builder.getDBStore();
    this.compactRangeQueue = builder.getCompactRangeQueue();
  }

  @Override
  public String getTableName() {
    return tableName;
  }

  @Override
  public void compactRange(KeyRange range) {
    try {
      LOG.info("Compacting range {} for table {}", range, tableName);
      dbStore.compactTable(tableName, range.getStartKey(), range.getEndKey());
    } catch (Exception e) {
      LOG.error("Failed to compact range {} for table {}", range, tableName, e);
    }
  }

  protected KeyRangeStats getRangeStats(KeyRange range) {
    try {
      Map<String, TableProperties> properties = dbStore.getPropertiesOfTableInRange(
          tableName, Collections.singletonList(range));

      KeyRangeStats stats = new KeyRangeStats(0, 0);
      for (TableProperties prop : properties.values()) {
        stats.add(KeyRangeStats.fromTableProperties(prop));
      }
      return stats;
    } catch (Exception e) {
      LOG.error("Failed to get range stats for range {} in table {}", range, tableName, e);
      return new KeyRangeStats(0, 0);
    }
  }

  @Override
  public List<KeyRange> getRangesNeedingCompaction() {
    List<KeyRange> ranges = new ArrayList<>();
    collectRangesNeedingCompaction(ranges);
    return ranges;
  }

  protected abstract void collectRangesNeedingCompaction(List<KeyRange> ranges);

  protected OMMetadataManager getMetadataManager() {
    return metadataManager;
  }

  protected long getMaxCompactionEntries() {
    return maxCompactionEntries;
  }

  protected int getRangesPerRun() {
    return rangesPerRun;
  }

  protected int getMinTombstones() {
    return minTombstones;
  }

  protected double getTombstoneRatio() {
    return tombstoneRatio;
  }

  protected DBStore getDBStore() {
    return dbStore;
  }

  protected void addRangeCompactionTask(KeyRange range) {
    compactRangeQueue.add(new CompactionTask(range));
  }

  protected String getKeyPrefixUpperBound(String keyPrefix) {
    char[] upperBoundCharArray = keyPrefix.toCharArray();
    upperBoundCharArray[upperBoundCharArray.length - 1] += 1;
    return String.valueOf(upperBoundCharArray);
  }

  private class CompactionTask implements BackgroundTask {
    private final KeyRange range;

    CompactionTask(KeyRange range) {
      this.range = range;
    }

    @Override
    public int getPriority() {
      // TODO: Implement priority based on some criteria
      return 0;
    }

    @Override
    public BackgroundTaskResult call() throws Exception {
      LOG.debug("Running compaction for range {} in table {}", range, tableName);

      dbStore.compactTable(tableName, range.getStartKey(), range.getEndKey());
      return () -> 1;
    }
  }
}
