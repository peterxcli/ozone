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
import org.apache.hadoop.hdds.utils.db.RocksDatabaseException;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.rocksdb.LiveFileMetaData;
import org.rocksdb.TableProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.Distribution.Range;

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
    long accumulatedEntries = 0;
    if (nextKey != null) {
      LOG.info("Last range has been splited, start from the next key {}", nextKey);
      // TODO: handle pagination
    }

    while (accumulatedEntries < getMaxEntriesSum()) {
      KeyRange bucketBound = getNextBucketBound();
      if (bucketBound == null && accumulatedEntries == 0) {
        // the last run may reach the end of the iterator, reset the iterator
        currentBucketUpperBound = null;
        bucketBound = getNextBucketBound();
      }

      if (bucketBound == null) {
        currentBucketUpperBound = null;
        break;
      }

      currentBucketUpperBound = bucketBound.getEndKey();
      KeyRangeStats stats = getRangeStats(bucketBound);

      if (accumulatedEntries + stats.getNumEntries() <= getMaxEntriesSum()) {
        // If the entire bucket fits within maxEntriesSum, check if it needs compaction
        if (needsCompaction(stats, getMinTombstones(), getTombstoneRatio())) {
          // TODO: merge consecutive ranges into one
          ranges.add(bucketBound);
          accumulatedEntries += stats.getNumEntries();
        }
      } else {
        LOG.info("Bucket {} is too large, need to split", bucketBound);
        // TODO:Need to split the bucket range
        // String splitKey = findSplitKey(bucketBound);
        // if (splitKey != null) {
          // KeyRange splitRange = new KeyRange(bucketKey, splitKey);
          // KeyRangeStats splitStats = getRangeStats(splitRange);
          // if (needsCompaction(splitStats, getMinTombstones(), getTombstoneRatio())) {
          //   ranges.add(splitRange);
          // }
          // nextBucket = bucketKey;
          // nextKey = splitKey;
          // break;
        // }
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
      bucketEndKey = getKeyPrefixUpperBound(bucketStartKey);
    }
    return new KeyRange(bucketStartKey, bucketEndKey);
  }

  private List<KeyRange> prepareRanges() throws IOException {
    // dd a function to prepare ranges first, start from `nextKey` or
    // `currentBucketBoundary`.
    // This can be done by getting the live file metadata list, then use
    // `getPropsOfTableInRange` to retrieve SSTs in range `[nextKey,
    // currentBucketBoundary]` or `[currentBucket, currentBucketBoundry]`,
    // and if there are too many entries in these ranges, we need to further spilt
    // them into smaller one, them save spilt point in `nextKey` for next round to
    // use.
    // In `collectRangesNeedingCompaction`, it call the previous
    List<KeyRange> ranges;
    long accumulatedEntries = 0;

    while (accumulatedEntries <= getMaxEntriesSum()) {
      if (nextKey != null) {
        // handle pagination
      }
    }
    String startKey, endKey;

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

  // private String findSplitKey(KeyRange range) {
  //   // Binary search to find a split key that keeps the range under maxEntriesSum
  //   String startKey = range.getStartKey();
  //   String endKey = range.getEndKey();

  //   // This is a simplified version - in reality, you'd need to implement
  //   // a proper binary search based on your key format
  //   return startKey + "\0";
  // }
}
