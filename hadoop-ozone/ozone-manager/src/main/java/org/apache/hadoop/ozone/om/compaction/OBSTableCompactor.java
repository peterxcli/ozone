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
    List<KeyRange> ranges = getRangesNeedingCompaction();
    for (KeyRange range : ranges) {
      addRangeCompactionTask(range);
    }
  }

  @Override
  protected void collectRangesNeedingCompaction(List<KeyRange> ranges) {
    for (int i = 0; i < getRangesPerRun(); i++) {
      try {
        Pair<KeyRange, KeyRangeStats> preparedRange = prepareRanges();
        if (preparedRange == null) {
          continue;
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
          ranges.add(preparedRange.getLeft());
        }
      } catch (IOException e) {
        LOG.error("Failed to prepare ranges for compaction", e);
      }
    }
  }

  /**
   * Get the next bucket bound.
   * 
   * @return the next bucket bound, or null if reach the end of the iterator
   */
  private KeyRange getNextBucketBound() {
    Iterator<Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>>> iterator = getMetadataManager()
        .getBucketIterator(currentBucketUpperBound);
    if (!iterator.hasNext()) {
      return null;
    }

    String bucketStartKey = iterator.next().getKey().getCacheKey(), bucketEndKey;
    if (iterator.hasNext()) {
      bucketEndKey = iterator.next().getKey().getCacheKey();
    } else {
      bucketEndKey = StringUtils.getKeyPrefixUpperBound(bucketStartKey);
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
    
    // Get the initial range to process
    KeyRange currentRange;
    if (nextKey != null) {
      // Continue from last split point
      currentRange = new KeyRange(nextKey, currentBucketUpperBound);
      // clean up the nextKey
      nextKey = null;
    } else {
      // Start from next bucket boundary
      KeyRange bucketBound = getNextBucketBound();
      if (bucketBound == null) {
        return null;
      }
      currentRange = new KeyRange(bucketBound.getStartKey(), bucketBound.getEndKey());
    }
    
    // Get compound stats for the range
    CompoundKeyRangeStats compoundStats = getCompoundKeyRangeStatsFromRange(currentRange);

    if (compoundStats.getNumEntries() <= getMaxCompactionEntries()) {
      return Pair.of(currentRange, compoundStats.getCompoundStats());
    } else {
      LOG.info("max compaction entries is {}, but range {} has {} entries, splitting",
          getMaxCompactionEntries(), currentRange, compoundStats.getNumEntries());
      List<Pair<KeyRange, KeyRangeStats>> splittedRanges = findFitRanges(
          compoundStats.getKeyRangeStatsList(), getMaxCompactionEntries());
      if (splittedRanges == null || splittedRanges.isEmpty()) {
        LOG.warn("splitted ranges is null or empty, return null, current range: {}", currentRange);
        return null;
      }
      Pair<KeyRange, KeyRangeStats> squashedRange = squashRanges(splittedRanges);
      String squashedRangeEndKeyUpperBound = StringUtils.getKeyPrefixUpperBound(squashedRange.getLeft().getEndKey());
      if (currentRange.getEndKey().compareTo(squashedRangeEndKeyUpperBound) > 0) {
        // if the squashed range is not the last range, then we need to split the range
        nextKey = squashedRangeEndKeyUpperBound;
        LOG.info("squashed range [{}] is not the last range, need to split, next key: {}",
            squashedRange, nextKey);
      } else {
        LOG.info("squashed range [{}] is the last range, no need to split, " +
            "probably means there are some SSTs that are ignored", squashedRange);
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

    ranges.sort((a, b) -> a.getLeft().getStartKey().compareTo(b.getLeft().getStartKey()));

    List<Pair<KeyRange, KeyRangeStats>> out = new ArrayList<>();

    KeyRangeStats chunkStats = new KeyRangeStats();

    for (Pair<KeyRange, KeyRangeStats> range : ranges) {
      long n = range.getRight().getNumEntries();

      // this single SST already exceeds the threshold
      if (n > maxEntries) {
        LOG.warn("Single SST [{}] holds {} entries (> maxEntries {}), it will be ignored.",
            range.getLeft(), n, maxEntries);
        continue;
      }

      // adding this SST would overflow the threshold
      if (chunkStats.getNumEntries() > 0 && chunkStats.getNumEntries() + n > maxEntries) {
        LOG.info("Chunk stats [{}] + range [{}]'s numEntries [{}] > maxEntries [{}], fit ranges found",
            chunkStats, range, n, maxEntries);
        return out;
      }

      /* add current SST into (possibly new) chunk */
      out.add(range);
      chunkStats.add(range.getRight());
    }

    return out;
  }

  /**
   * Build a KeyRange (start inclusive, end exclusive) that encloses the
   * SSTs between firstIdx and lastIdx (inclusive).  Uses
   * getKeyPrefixUpperBound() to make the end key exclusive.
   */
  private Pair<KeyRange, KeyRangeStats> squashRanges(
          List<Pair<KeyRange, KeyRangeStats>> list) {
    Preconditions.checkNotNull(list, "list is null");
    Preconditions.checkArgument(!list.isEmpty(), "list is empty");

    String minKey = list.get(0).getLeft().getStartKey(), maxKey = list.get(0).getLeft().getEndKey();
    KeyRangeStats stats = new KeyRangeStats();
    for (Pair<KeyRange, KeyRangeStats> range : list) {
      minKey = StringUtils.min(minKey, range.getLeft().getStartKey());
      maxKey = StringUtils.max(maxKey, range.getLeft().getEndKey());
      stats.add(range.getRight());
    }
    return Pair.of(new KeyRange(minKey, maxKey), stats);
  }

  private CompoundKeyRangeStats getCompoundKeyRangeStatsFromRange(KeyRange range) throws IOException {
    List<Pair<KeyRange, KeyRangeStats>> keyRangeStatsList = new ArrayList<>();
    List<LiveFileMetaData> liveFileMetaDataList = ((RDBStore)getDBStore()).getDb().getLiveFilesMetaData();
    Map<String, LiveFileMetaData> fileMap = new HashMap<>();
    // https://github.com/facebook/rocksdb/blob/09cd25f76305f2110131f51068656ab392dc2bf5/db/version_set.cc#L1744-L1768
    // https://github.com/facebook/rocksdb/blob/a00391c72996a5dbdd93a621dbc53719c13b05c4/file/filename.cc#L140-L150
    for (LiveFileMetaData metaData : liveFileMetaDataList) {
      fileMap.put(metaData.fileName(), metaData);
    }

    Map<String, TableProperties> propsMap = getDBStore().getPropertiesOfTableInRange(getTableName(),
        Collections.singletonList(range));

    for (Map.Entry<String, TableProperties> entry : propsMap.entrySet()) {
      String fullPath = entry.getKey();
      String fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
      LiveFileMetaData meta = fileMap.get(fileName);
      if (meta == null) {
        LOG.warn("LiveFileMetaData not found for file {}", fullPath);
        continue;
      }
      KeyRange keyRange = new KeyRange(meta.smallestKey(), meta.largestKey());
      KeyRangeStats stats = KeyRangeStats.fromTableProperties(entry.getValue());
      keyRangeStatsList.add(Pair.of(keyRange, stats));
    }
    return new CompoundKeyRangeStats(keyRangeStatsList);
  }
}
