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

package org.apache.hadoop.ozone.om.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.utils.BackgroundService;
import org.apache.hadoop.hdds.utils.BackgroundTaskQueue;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.compaction.Compactor;
import org.apache.hadoop.ozone.om.compaction.CompactorBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages all compactors and schedules their execution.
 */
public class RangeCompactionService extends BackgroundService {
  private static final Logger LOG = LoggerFactory.getLogger(RangeCompactionService.class);
  private static final int THREAD_POOL_SIZE = 1;

  private final OMMetadataManager metadataManager;
  private final List<Compactor> compactors;
  private final Map<String, ScheduledExecutorService> compactorExecutors;
  private final long checkIntervalMs;
  private final long maxCompactionEntries;
  private final int rangesPerRun;
  private final int minTombstones;
  private final double tombstoneRatio;

  private final BackgroundTaskQueue tasks;

  public RangeCompactionService(OzoneConfiguration conf, OzoneManager ozoneManager, long intervalMs,
      long timeoutMs) {
    super("RangeCompactionService", intervalMs, TimeUnit.MILLISECONDS, THREAD_POOL_SIZE, timeoutMs,
        ozoneManager.getThreadNamePrefix());
    this.metadataManager = ozoneManager.getMetadataManager();
    this.checkIntervalMs = intervalMs;
    this.maxCompactionEntries = conf.getLong(OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_MAX_COMPACTION_ENTRIES,
        OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_MAX_COMPACTION_ENTRIES_DEFAULT);
    this.rangesPerRun = conf.getInt(OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_RANGES_PER_RUN,
        OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_RANGES_PER_RUN_DEFAULT);
    this.minTombstones = conf.getInt(OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_MIN_TOMBSTONES,
        OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_MIN_TOMBSTONES_DEFAULT);
    this.tombstoneRatio = conf.getDouble(OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_TOMBSTONE_RATIO,
        OMConfigKeys.OZONE_OM_RANGE_COMPACTION_SERVICE_TOMBSTONE_RATIO_DEFAULT);
    this.compactors = new ArrayList<>();
    this.compactorExecutors = new ConcurrentHashMap<>();

    this.tasks = new BackgroundTaskQueue();
    initializeCompactors();
  }

  @Override
  public BackgroundTaskQueue getTasks() {
    return tasks;
  }

  @Override
  public synchronized void start() {
    super.start();
    scheduleCompactionChecks();
  }

  public void stop() {
    for (Map.Entry<String, ScheduledExecutorService> entry : compactorExecutors.entrySet()) {
      ScheduledExecutorService executor = entry.getValue();
      executor.shutdown();
      try {
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    compactorExecutors.clear();
  }

  private void initializeCompactors() {
    // Initialize compactors using the builder pattern
    CompactorBuilder builder = new CompactorBuilder()
        .setCompactRangeQueue(tasks)
        .setMetadataManager(metadataManager)
        .setDBStore(metadataManager.getStore())
        .setMaxCompactionEntries(maxCompactionEntries)
        .setMinTombstones(minTombstones)
        .setTombstoneRatio(tombstoneRatio)
        .setRangesPerRun(rangesPerRun);

    // Initialize compactors for OBS and legacy layout
    try {
      compactors.add(builder.setTableName("keyTable")
          .build());
      compactors.add(builder.setTableName("deletedTable")
          .build());

      // Initialize compactors for FSO layout
      compactors.add(builder.setTableName("directoryTable")
          .build());
      compactors.add(builder.setTableName("fileTable")
          .build());
      compactors.add(builder.setTableName("deletedDirectoryTable")
          .build());
    } catch (IOException e) {
      LOG.error("Failed to initialize compactors", e);
      throw new RuntimeException("Failed to initialize compactors", e);
    }
  }

  private void scheduleCompactionChecks() {
    for (Compactor compactor : compactors) {
      String tableName = compactor.getTableName();
      ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
      compactorExecutors.put(tableName, executor);
      
      LOG.info("Scheduling range compaction compactor for table {}", tableName);
      executor.scheduleWithFixedDelay(
          compactor::run,
          checkIntervalMs, // TODO: randomize the start time
          checkIntervalMs,
          TimeUnit.MILLISECONDS);
    }
  }
}

