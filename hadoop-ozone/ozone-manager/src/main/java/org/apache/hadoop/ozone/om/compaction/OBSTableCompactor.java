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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.StringUtils;
import org.apache.hadoop.hdds.utils.db.KeyRange;
import org.apache.hadoop.hdds.utils.db.RDBStore;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.rocksdb.LiveFileMetaData;
import org.rocksdb.TableProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compactor for OBS and legacy layout that compacts based on bucket ranges.
 */
public class OBSTableCompactor extends AbstractCompactor {
  private static final Logger LOG = LoggerFactory.getLogger(OBSTableCompactor.class);

  private String currentBucketUpperBound;
  private String nextKey;

  public OBSTableCompactor(CompactorBuilder builder) {
    super(builder);
  }

  @Override
  public void run() {
    try {
      LOG.info("Starting range compaction scan for table {}", getTableName());
      List<KeyRange> ranges = getRangesNeedingCompaction();
      LOG.info("Found {} ranges needing compaction for table {}, submitting to compaction queue", 
          ranges.size(), getTableName());
      for (KeyRange range : ranges) {
        LOG.info("Submitting range [{}] for compaction", range);
        addRangeCompactionTask(range);
      }
      LOG.info("Completed range compaction scan for table {}", getTableName());
    } catch (Exception e) {
      LOG.error("Failed to run range compaction for table {}", getTableName(), e);
    }
  }

  @Override
  protected void collectRangesNeedingCompaction(List<KeyRange> ranges) {
    LOG.info("Starting to collect ranges needing compaction for table {}", getTableName());
    for (int i = 0; i < getRangesPerRun(); i++) {
      try {
        LOG.info("Preparing range {} of {} for table {}", i + 1, getRangesPerRun(), getTableName());
        Pair<KeyRange, KeyRangeStats> preparedRange = prepareRanges();
        if (preparedRange == null) {
          LOG.info("No more ranges to prepare for table {}", getTableName());
          break;
        }
        // TODO: merge consecutive ranges if they aren't too big, notice the situation that iterator has been reset
        // to be more specific, 
        // constain 1: temp start key and end key are either null or not null at the same time, called them temp range
        // constain 2: if temp range is submitted, clean temp range
        // if range is null:
        //   1. if temp range is not null, then submit and reset the temp range
        //   otherwise, do nothing
        // if range is a spiltted range:
        //   1. if temp range is null, submit the range directly
        //   2. if temp range is not null and temp accumlated numEntries plus the current range's numEntries 
        //      is greater than maxEntries, then submit **temp range and the current range**
        //   3. if temp range is not null, and temp accumlated numEntries plus the current range's numEntries 
        //      is less than maxEntries, then submit **a range that composed of temp range and the current range**
        //      (notice the difference between point 2, 3)
        // if range is not a splitted range:
        //   1. if temp range is null, update it to the current range's start key, and temp end key as well
        //   2. if temp range is not null and temp accumlated numEntries plus the current range's numEntries 
        //     is greater than maxEntries, then submit temp range and set temp range to the current range
        //   3. if temp range is not null and temp accumlated numEntries plus the current range's numEntries 
        //      is less than maxEntries, 
        //      then **set temp range to a range that composed of temp range and the current range**
        //      (notice the difference between point 2, 3)
        if (needsCompaction(preparedRange.getRight(), getMinTombstones(), getTombstoneRatio())) {
          LOG.info("Range [{}] needs compaction with stats: {}", preparedRange.getLeft(), preparedRange.getRight());
          ranges.add(preparedRange.getLeft());
        } else {
          LOG.info("Range [{}] does not need compaction with stats: {}", preparedRange.getLeft(),
              preparedRange.getRight());
        }
      } catch (IOException e) {
        LOG.error("Failed to prepare ranges for compaction for table {}", getTableName(), e);
      }
    }
    LOG.info("Collected {} ranges needing compaction for table {}", ranges.size(), getTableName());
  }

  /**
   * Get the next bucket bound.
   * 
   * @return the next bucket bound, or null if reach the end of the iterator
   */
  private KeyRange getNextBucketBound() {
    LOG.info("Getting next bucket bound starting from {}", currentBucketUpperBound);
    Iterator<Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>>> iterator = getMetadataManager()
        .getBucketIterator(currentBucketUpperBound);
    if (!iterator.hasNext()) {
      LOG.info("No more bucket bounds found");
      currentBucketUpperBound = null;
      return null;
    }

    String bucketStartKey = iterator.next().getKey().getCacheKey(), bucketEndKey;
    if (iterator.hasNext()) {
      bucketEndKey = iterator.next().getKey().getCacheKey();
      currentBucketUpperBound = bucketEndKey;
      LOG.info("Found bucket range: [{} - {}]", bucketStartKey, bucketEndKey);
    } else {
      bucketEndKey = StringUtils.getKeyPrefixUpperBound(bucketStartKey);
      currentBucketUpperBound = bucketEndKey;
      LOG.info("Found last bucket range: [{} - {}]", bucketStartKey, bucketEndKey);
    }
    return new KeyRange(bucketStartKey, bucketEndKey);
  }

  /**
   * Prepare ranges for compaction.
   * 
   * @return the prepared ranges, the range and its stats in pair may be **combined** by multiple ranges
   * @throws IOException if an error occurs
   */
  private Pair<KeyRange, KeyRangeStats> prepareRanges() throws IOException {
    LOG.info("Preparing ranges for compaction");
    
    // Get the initial range to process
    KeyRange currentRange;
    if (nextKey != null) {
      // Continue from last split point
      LOG.info("Continuing from last split point: {}", nextKey);
      currentRange = new KeyRange(nextKey, currentBucketUpperBound);
      // clean up the nextKey
      nextKey = null;
    } else {
      // Start from next bucket boundary
      KeyRange bucketBound = getNextBucketBound();
      if (bucketBound == null) {
        LOG.info("No more bucket bounds to process");
        return null;
      }
      currentRange = new KeyRange(bucketBound.getStartKey(), bucketBound.getEndKey());
      LOG.info("Starting new bucket range: [{}]", currentRange);
    }
    
    // Get compound stats for the range
    CompoundKeyRangeStats compoundStats = getCompoundKeyRangeStatsFromRange(currentRange);
    if (compoundStats.isEmpty()) {
      LOG.info("Range [{}] has no entries, returning empty range", currentRange);
      return Pair.of(currentRange, new KeyRangeStats());
    }
    LOG.info("Range [{}] has {} entries", currentRange, compoundStats.getNumEntries());

    if (compoundStats.getNumEntries() <= getMaxCompactionEntries()) {
      LOG.info("Range [{}] fits within max entries limit of {}", currentRange, getMaxCompactionEntries());
      return Pair.of(currentRange, compoundStats.getCompoundStats());
    } else {
      LOG.info("Range [{}] exceeds max entries limit ({} > {}), splitting", 
          currentRange, compoundStats.getNumEntries(), getMaxCompactionEntries());
      List<Pair<KeyRange, KeyRangeStats>> splittedRanges = findFitRanges(
          compoundStats.getKeyRangeStatsList(), getMaxCompactionEntries());
      if (splittedRanges == null || splittedRanges.isEmpty()) {
        LOG.info("Failed to split range [{}] into valid sub-ranges, returning original range", currentRange);
        return Pair.of(currentRange, compoundStats.getCompoundStats());
      }
      Pair<KeyRange, KeyRangeStats> squashedRange = squashRanges(splittedRanges);
      String squashedRangeEndKeyUpperBound = StringUtils.getKeyPrefixUpperBound(squashedRange.getLeft().getEndKey());
      if (currentRange.getEndKey().compareTo(squashedRangeEndKeyUpperBound) > 0) {
        // if the squashed range is not the last range, then we need to split the range
        nextKey = squashedRangeEndKeyUpperBound;
        LOG.info("Split range [{}] into [{}] and will continue from {}", 
            currentRange, squashedRange.getLeft(), nextKey);
      } else {
        LOG.info("Split range [{}] into [{}] (final range)", currentRange, squashedRange.getLeft());
      }
      return squashedRange;
    }
  }

  /**
   * return a list of ranges that each range's numEntries ≤ maxEntries,
   * and their sum of numEntries are not greater than maxEntries, too.
   */
  private List<Pair<KeyRange, KeyRangeStats>> findFitRanges(
          List<Pair<KeyRange, KeyRangeStats>> ranges, long maxEntries) {
    LOG.info("Finding ranges that fit within {} entries", maxEntries);
    ranges.sort((a, b) -> a.getLeft().getStartKey().compareTo(b.getLeft().getStartKey()));

    List<Pair<KeyRange, KeyRangeStats>> out = new ArrayList<>();
    KeyRangeStats chunkStats = new KeyRangeStats();

    for (Pair<KeyRange, KeyRangeStats> range : ranges) {
      long n = range.getRight().getNumEntries();
      LOG.info("Processing range [{}] with {} entries", range.getLeft(), n);

      // this single SST already exceeds the threshold
      if (n > maxEntries) {
        LOG.warn("Range [{}] exceeds max entries ({} > {}), skipping", range.getLeft(), n, maxEntries);
        continue;
      }

      // adding this SST would overflow the threshold
      if (chunkStats.getNumEntries() > 0 && chunkStats.getNumEntries() + n > maxEntries) {
        LOG.info("Current chunk ({} entries) + range [{}] ({} entries) exceeds max entries ({}), stopping", 
            chunkStats.getNumEntries(), range.getLeft(), n, maxEntries);
        return out;
      }

      /* add current SST into (possibly new) chunk */
      out.add(range);
      chunkStats.add(range.getRight());
      LOG.info("Added range [{}] to chunk, total entries: {}", range.getLeft(), chunkStats.getNumEntries());
    }

    LOG.info("Found {} ranges that fit within {} entries", out.size(), maxEntries);
    return out;
  }

  /**
   * Build a KeyRange (start inclusive, end exclusive) that encloses the
   * SSTs between firstIdx and lastIdx (inclusive).  Uses
   * getKeyPrefixUpperBound() to make the end key exclusive.
   */
  private Pair<KeyRange, KeyRangeStats> squashRanges(
          List<Pair<KeyRange, KeyRangeStats>> list) {
    LOG.info("Squashing {} ranges", list.size());
    Preconditions.checkNotNull(list, "list is null");
    Preconditions.checkArgument(!list.isEmpty(), "list is empty");

    String minKey = list.get(0).getLeft().getStartKey(), maxKey = list.get(0).getLeft().getEndKey();
    KeyRangeStats stats = new KeyRangeStats();
    for (Pair<KeyRange, KeyRangeStats> range : list) {
      minKey = StringUtils.min(minKey, range.getLeft().getStartKey());
      maxKey = StringUtils.max(maxKey, range.getLeft().getEndKey());
      stats.add(range.getRight());
      LOG.info("Squashing range [{}] with {} entries", range.getLeft(), range.getRight().getNumEntries());
    }
    KeyRange squashedRange = new KeyRange(minKey, maxKey);
    LOG.info("Squashed {} ranges into [{}] with {} total entries", 
        list.size(), squashedRange, stats.getNumEntries());
    return Pair.of(squashedRange, stats);
  }

  private CompoundKeyRangeStats getCompoundKeyRangeStatsFromRange(KeyRange range) throws IOException {
    LOG.info("Getting compound stats for range [{}]", range);
    List<Pair<KeyRange, KeyRangeStats>> keyRangeStatsList = new ArrayList<>();
    List<LiveFileMetaData> liveFileMetaDataList = ((RDBStore)getDBStore()).getDb().getLiveFilesMetaData();
    Map<String, LiveFileMetaData> fileMap = new HashMap<>();
    // https://github.com/facebook/rocksdb/blob/09cd25f76305f2110131f51068656ab392dc2bf5/db/version_set.cc#L1744-L1768
    // https://github.com/facebook/rocksdb/blob/a00391c72996a5dbdd93a621dbc53719c13b05c4/file/filename.cc#L140-L150
    LOG.info("Processing {} live files", liveFileMetaDataList.size());
    for (LiveFileMetaData metaData : liveFileMetaDataList) {
      fileMap.put(metaData.path() + metaData.fileName(), metaData);
    }

    LOG.info("Getting table properties for range [{}]", range);
    Map<String, TableProperties> propsMap = getDBStore().getPropertiesOfTableInRange(getTableName(),
        Collections.singletonList(range));
    LOG.info("Found {} table properties in range", propsMap.size());

    for (Map.Entry<String, TableProperties> entry : propsMap.entrySet()) {
      String filePath = entry.getKey();
      LiveFileMetaData meta = fileMap.get(filePath);
      if (meta == null) {
        LOG.warn("LiveFileMetaData not found for file {}", filePath);
        continue;
      }
      KeyRange keyRange = new KeyRange(meta.smallestKey(), meta.largestKey());
      KeyRangeStats stats = KeyRangeStats.fromTableProperties(entry.getValue());
      keyRangeStatsList.add(Pair.of(keyRange, stats));
      LOG.info("Added file {} with range [{}] and {} entries", 
          filePath, keyRange, stats.getNumEntries());
    }

    LOG.info("Key range stats list: {}", keyRangeStatsList);
    
    CompoundKeyRangeStats result = new CompoundKeyRangeStats(keyRangeStatsList);
    if (!result.isEmpty()) {
      LOG.info("Compound stats for range [{}]: {} entries across {} files", 
          range, result.getNumEntries(), keyRangeStatsList.size());
    } else {
      LOG.info("Range [{}] has no entries", range);
    }
    return result;
  }
}
