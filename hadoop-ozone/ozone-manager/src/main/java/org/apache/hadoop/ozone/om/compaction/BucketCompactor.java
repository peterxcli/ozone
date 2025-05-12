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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hdds.utils.db.KeyRange;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compactor for OBS and legacy layout that compacts based on bucket ranges.
 */
public class BucketCompactor extends AbstractCompactor {
  private static final Logger LOG = LoggerFactory.getLogger(BucketCompactor.class);

  private String nextBucket;
  private String nextKey;

  public BucketCompactor(CompactorBuilder builder) {
    super(builder);
  }

  @Override
  public void run() {
    
  }

  @Override
  protected void collectRangesNeedingCompaction(List<KeyRange> ranges) {
    Iterator<Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>>> bucketIterator = getBucketIterator();
    while (bucketIterator.hasNext()) {
      Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>> entry = bucketIterator.next();
      String bucketKey = entry.getKey().getCacheKey();
      OmBucketInfo bucketInfo = entry.getValue().getCacheValue();

      if (nextBucket != null && !nextBucket.equals(bucketKey)) {
        continue;
      }

      KeyRange bucketRange = new KeyRange(bucketKey, getNextBucketKey(bucketKey));
      KeyRangeStats stats = getRangeStats(bucketRange);

      if (stats.getNumEntries() <= getMaxEntriesSum()) {
        // If the entire bucket fits within maxEntriesSum, check if it needs compaction
        if (needsCompaction(stats, getMinTombstones(), getTombstoneRatio())) {
          ranges.add(bucketRange);
        }
        nextBucket = null;
        nextKey = null;
      } else {
        // Need to split the bucket range
        String splitKey = findSplitKey(bucketRange);
        if (splitKey != null) {
          KeyRange splitRange = new KeyRange(bucketKey, splitKey);
          KeyRangeStats splitStats = getRangeStats(splitRange);
          if (needsCompaction(splitStats, getMinTombstones(), getTombstoneRatio())) {
            ranges.add(splitRange);
          }
          nextBucket = bucketKey;
          nextKey = splitKey;
          break;
        }
      }
    }
  }

  private Iterator<Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>>> getBucketIterator() {
    if (nextBucket != null) {
      return getMetadataManager().getBucketIterator(nextBucket);
    }
    return getMetadataManager().getBucketIterator();
  }

  private String getNextBucketKey(String currentBucketKey) {
    // For OBS and legacy layout, the next bucket key is the current bucket key + 1
    // This is a simplified version - in reality, you'd need to handle the key
    // format
    // based on your specific key structure
    return currentBucketKey + "\0";
  }

  private String findSplitKey(KeyRange range) {
    // Binary search to find a split key that keeps the range under maxEntriesSum
    String startKey = range.getStartKey();
    String endKey = range.getEndKey();

    // This is a simplified version - in reality, you'd need to implement
    // a proper binary search based on your key format
    return startKey + "\0";
  }
}
