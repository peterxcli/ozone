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

import java.io.IOException;
import org.apache.hadoop.hdds.utils.BackgroundTaskQueue;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.codec.OMDBDefinition;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;

/**
 * Builder class for creating compactors based on table names.
 */
public class CompactorBuilder {
  private DBStore db;
  private BackgroundTaskQueue compactRangeQueue;
  private String tableName;
  private int maxEntriesSum;
  private int minTombstones;
  private double tombstoneRatio;

  private OMMetadataManager metadataManager;

  public CompactorBuilder setCompactRangeQueue(BackgroundTaskQueue compactRangeQueue) {
    this.compactRangeQueue = compactRangeQueue;
    return this;
  }

  public CompactorBuilder setMetadataManager(OMMetadataManager metadataManager) {
    this.metadataManager = metadataManager;
    return this;
  }

  public CompactorBuilder setDBStore(DBStore dbStore) {
    this.db = dbStore;
    return this;
  }

  public CompactorBuilder setTableName(String tableName) {
    this.tableName = tableName;
    return this;
  }

  public CompactorBuilder setMaxEntriesSum(int maxEntriesSum) {
    this.maxEntriesSum = maxEntriesSum;
    return this;
  }

  public CompactorBuilder setMinTombstones(int minTombstones) {
    this.minTombstones = minTombstones;
    return this;
  }

  public CompactorBuilder setTombstoneRatio(double tombstoneRatio) {
    this.tombstoneRatio = tombstoneRatio;
    return this;
  }

  public Compactor build() throws IOException {
    if (tableName == null) {
      throw new IllegalArgumentException("Name and table must be set");
    }

    BucketLayout layout = OMDBDefinition.getBucketLayoutForTable(tableName);
    if (layout == null) {
      throw new IllegalArgumentException("No bucket layout mapping found for table: " + tableName);
    }

    switch (layout) {
    case FILE_SYSTEM_OPTIMIZED:
      return new FSOTableCompactor(this);
    case OBJECT_STORE:
      return new OBSTableCompactor(this);
    default:
      throw new IllegalArgumentException("Unsupported bucket layout: " + layout);
    }
  }
  
  public String getTableName() {
    return tableName;
  }

  public int getMaxEntriesSum() {
    return maxEntriesSum;
  }

  public int getMinTombstones() {
    return minTombstones;
  }

  public double getTombstoneRatio() {
    return tombstoneRatio;
  }

  public DBStore getDBStore() {
    return db;
  }

  public OMMetadataManager getMetadataManager() {
    return metadataManager;
  }

  public BackgroundTaskQueue getCompactRangeQueue() {
    return compactRangeQueue;
  }
}
