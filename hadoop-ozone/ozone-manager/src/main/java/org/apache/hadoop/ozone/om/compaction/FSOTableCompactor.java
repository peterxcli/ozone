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
 * Compactor for FSO layout that compacts based on bucket and parent ID ranges.
 */
public class FSOTableCompactor extends AbstractCompactor {
  private static final Logger LOG = LoggerFactory.getLogger(FSOTableCompactor.class);

  private String nextBucket;
  private String nextParentId;
  private String nextKey;

  public FSOTableCompactor(CompactorBuilder builder) {
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

      // For FSO, we need to handle parent IDs
      if (nextParentId != null) {
        // Continue with the same bucket but different parent ID
        KeyRange parentRange = new KeyRange(
            getParentKey(bucketKey, nextParentId),
            getNextParentKey(bucketKey, nextParentId));
        processRange(parentRange, ranges);
      } else {
        // Start with the first parent ID for this bucket
        String firstParentId = getFirstParentId(bucketKey);
        if (firstParentId != null) {
          KeyRange parentRange = new KeyRange(
              getParentKey(bucketKey, firstParentId),
              getNextParentKey(bucketKey, firstParentId));
          processRange(parentRange, ranges);
        }
      }
    }
  }

  private void processRange(KeyRange range, List<KeyRange> ranges) {
    KeyRangeStats stats = getRangeStats(range);

    if (stats.getNumEntries() <= getMaxEntriesSum()) {
      if (needsCompaction(stats, getMinTombstones(), getTombstoneRatio())) {
        ranges.add(range);
      }
      nextParentId = null;
      nextKey = null;
    } else {
      String splitKey = findSplitKey(range);
      if (splitKey != null) {
        KeyRange splitRange = new KeyRange(range.getStartKey(), splitKey);
        KeyRangeStats splitStats = getRangeStats(splitRange);
        if (needsCompaction(splitStats, getMinTombstones(), getTombstoneRatio())) {
          ranges.add(splitRange);
        }
        nextKey = splitKey;
      }
    }
  }

  private Iterator<Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>>> getBucketIterator() {
    if (nextBucket != null) {
      return getMetadataManager().getBucketIterator(nextBucket);
    }
    return getMetadataManager().getBucketIterator();
  }

  private String getParentKey(String bucketKey, String parentId) {
    // Format: /volumeId/bucketId/parentId/
    return bucketKey + "/" + parentId + "/";
  }

  private String getNextParentKey(String bucketKey, String parentId) {
    // Format: /volumeId/bucketId/parentId/ + 1
    return bucketKey + "/" + parentId + "/\0";
  }

  private String getFirstParentId(String bucketKey) {
    // This is a simplified version - in reality, you'd need to implement
    // a proper way to get the first parent ID for a bucket
    return "0";
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
