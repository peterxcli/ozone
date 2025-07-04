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
import java.util.NavigableMap;
import java.util.TreeMap;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.StringUtils;
import org.apache.hadoop.hdds.utils.db.KeyRange;
import org.apache.hadoop.hdds.utils.db.RDBStore;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.db.TableIterator;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmDirectoryInfo;
import org.rocksdb.LiveFileMetaData;
import org.rocksdb.TableProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compactor for FSO layout that compacts based on bucket and parent ID ranges.
 * 
 * Unlike OBS compactor which uses bucket boundaries, FSO compactor uses a hierarchical
 * approach based on parent-child relationships in the directory structure.
 * 
 * Key features:
 * 1. Parent ID-based splitting: Creates ranges based on directory hierarchy
 * 2. Depth-aware compaction: Prioritizes shallow directories over deep ones
 * 3. Directory-aware merging: Keeps related files/subdirectories together
 * 4. Adaptive range sizing: Adjusts range size based on directory entry count
 */
public class FSOTableCompactor extends AbstractCompactor {
  private static final Logger LOG = LoggerFactory.getLogger(FSOTableCompactor.class);
  private static final String OM_KEY_PREFIX = "/";
  
  // Pagination state for resuming compaction
  private Long currentVolumeId;
  private Long currentBucketId;
  private Long currentParentId;
  private String resumeKey;
  
  // Cache for directory hierarchy information
  private final NavigableMap<Long, DirectoryMetadata> directoryCache = new TreeMap<>();
  
  // Configuration for adaptive splitting
  private static final int MAX_DIRECTORY_DEPTH = 10;
  private static final double DEPTH_WEIGHT_FACTOR = 0.8; // Prioritize shallow directories

  public FSOTableCompactor(CompactorBuilder builder) {
    super(builder);
  }
  
  /**
   * Represents cached metadata about a directory.
   */
  private static class DirectoryMetadata {
    private final long objectId;
    private final long parentId;
    private final int depth;
    private final String path;
    private long entryCount;
    private long tombstoneCount;
    
    DirectoryMetadata(long objectId, long parentId, int depth, String path) {
      this.objectId = objectId;
      this.parentId = parentId;
      this.depth = depth;
      this.path = path;
      this.entryCount = 0;
      this.tombstoneCount = 0;
    }
    
    double getCompactionPriority(double tombstoneRatio) {
      if (entryCount == 0) return 0;
      double tombstoneScore = (double) tombstoneCount / entryCount;
      // Prioritize shallow directories by reducing score based on depth
      return tombstoneScore * Math.pow(DEPTH_WEIGHT_FACTOR, depth);
    }
  }

  @Override
  public void run() {
    try {
      LOG.info("Starting FSO range compaction scan for table {}", getTableName());
      List<KeyRange> ranges = getRangesNeedingCompaction();
      LOG.info("Found {} ranges needing compaction for table {}, submitting to compaction queue", 
          ranges.size(), getTableName());
      for (KeyRange range : ranges) {
        LOG.info("Submitting range [{}] for compaction", range);
        addRangeCompactionTask(range);
      }
      LOG.info("Completed FSO range compaction scan for table {}", getTableName());
    } catch (Exception e) {
      LOG.error("Failed to run FSO range compaction for table {}", getTableName(), e);
    }
  }

  @Override
  protected void collectRangesNeedingCompaction(List<KeyRange> ranges) {
    LOG.info("Starting to collect FSO ranges needing compaction for table {}", getTableName());
    
    try {
      // First, build directory hierarchy cache
      buildDirectoryCache();
      
      // Process ranges based on directory hierarchy
      for (int i = 0; i < getRangesPerRun(); i++) {
        Pair<KeyRange, KeyRangeStats> rangeAndStats = prepareNextRange();
        if (rangeAndStats == null) {
          LOG.info("No more ranges to process for table {}", getTableName());
          break;
        }
        
        if (needsCompaction(rangeAndStats.getRight(), getMinTombstones(), getTombstoneRatio())) {
          LOG.info("Range [{}] needs compaction with stats: {}", 
              rangeAndStats.getLeft(), rangeAndStats.getRight());
          ranges.add(rangeAndStats.getLeft());
        } else {
          LOG.info("Range [{}] does not need compaction with stats: {}", 
              rangeAndStats.getLeft(), rangeAndStats.getRight());
        }
      }
    } catch (IOException e) {
      LOG.error("Failed to collect FSO ranges for compaction for table {}", getTableName(), e);
    }
    
    LOG.info("Collected {} FSO ranges needing compaction for table {}", ranges.size(), getTableName());
  }
  
  /**
   * Build a cache of directory metadata for efficient range calculation.
   */
  private void buildDirectoryCache() throws IOException {
    if (getTableName().equals(getMetadataManager().getDirectoryTableName())) {
      LOG.info("Building directory cache for FSO compaction");
      
      Table<String, OmDirectoryInfo> dirTable = getMetadataManager().getDirectoryTable();
      try (TableIterator<String, ? extends Table.KeyValue<String, OmDirectoryInfo>> iter = 
          dirTable.iterator()) {
        
        int count = 0;
        while (iter.hasNext() && count < 1000) { // Limit cache size
          Table.KeyValue<String, OmDirectoryInfo> kv = iter.next();
          OmDirectoryInfo dirInfo = kv.getValue();
          
          // Calculate depth by counting parent traversals
          int depth = calculateDirectoryDepth(dirInfo);
          DirectoryMetadata metadata = new DirectoryMetadata(
              dirInfo.getObjectID(), 
              dirInfo.getParentObjectID(),
              depth,
              kv.getKey()
          );
          
          directoryCache.put(dirInfo.getObjectID(), metadata);
          count++;
        }
      }
      
      LOG.info("Built directory cache with {} entries", directoryCache.size());
    }
  }
  
  /**
   * Calculate the depth of a directory in the hierarchy.
   */
  private int calculateDirectoryDepth(OmDirectoryInfo dirInfo) {
    int depth = 0;
    long parentId = dirInfo.getParentObjectID();
    
    // Traverse up the hierarchy until we reach bucket level
    while (depth < MAX_DIRECTORY_DEPTH && directoryCache.containsKey(parentId)) {
      DirectoryMetadata parent = directoryCache.get(parentId);
      parentId = parent.parentId;
      depth++;
    }
    
    return depth;
  }
  
  /**
   * Prepare the next range for compaction based on FSO hierarchy.
   */
  private Pair<KeyRange, KeyRangeStats> prepareNextRange() throws IOException {
    LOG.info("Preparing next FSO range for compaction");
    
    // Determine starting point
    KeyRange currentRange;
    if (resumeKey != null) {
      // Resume from last split point
      LOG.info("Resuming from last split point: {}", resumeKey);
      String endKey = findNextParentBoundary(resumeKey);
      currentRange = new KeyRange(resumeKey, endKey);
      resumeKey = null;
    } else {
      // Find next parent ID range to process
      currentRange = getNextParentIdRange();
      if (currentRange == null) {
        return null;
      }
    }
    
    // Get stats and handle splitting if needed
    return processRangeWithSplitting(currentRange);
  }
  
  /**
   * Get the next parent ID range to process.
   */
  private KeyRange getNextParentIdRange() throws IOException {
    LOG.info("Getting next parent ID range");
    
    // If we have a current position, continue from there
    if (currentVolumeId != null && currentBucketId != null && currentParentId != null) {
      return getNextParentRange(currentVolumeId, currentBucketId, currentParentId);
    }
    
    // Otherwise, start from the beginning
    return getFirstParentRange();
  }
  
  /**
   * Get the first parent range in the table.
   */
  private KeyRange getFirstParentRange() throws IOException {
    // For FSO tables, we need to iterate through volume/bucket combinations
    Iterator<Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>>> bucketIter = 
        getMetadataManager().getBucketIterator();
    
    if (!bucketIter.hasNext()) {
      LOG.info("No buckets found for FSO compaction");
      return null;
    }
    
    Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>> bucketEntry = bucketIter.next();
    OmBucketInfo bucketInfo = bucketEntry.getValue().getCacheValue();
    
    currentVolumeId = bucketInfo.getVolumeId();
    currentBucketId = bucketInfo.getObjectID();
    currentParentId = bucketInfo.getObjectID(); // Start with bucket as parent
    
    String startKey = buildFSOKey(currentVolumeId, currentBucketId, currentParentId, "");
    String endKey = buildFSOKey(currentVolumeId, currentBucketId, currentParentId + 1, "");
    
    LOG.info("First parent range: volumeId={}, bucketId={}, parentId={}, range=[{} - {}]",
        currentVolumeId, currentBucketId, currentParentId, startKey, endKey);
    
    return new KeyRange(startKey, endKey);
  }
  
  /**
   * Get the next parent range after the current one.
   */
  private KeyRange getNextParentRange(long volumeId, long bucketId, long parentId) throws IOException {
    // For simplicity, increment parent ID
    // In production, this would involve more sophisticated directory traversal
    currentParentId = parentId + 1;
    
    // Check if we need to move to next bucket
    if (currentParentId > parentId + 1000) { // Arbitrary limit for demo
      return moveToNextBucket();
    }
    
    String startKey = buildFSOKey(volumeId, bucketId, currentParentId, "");
    String endKey = buildFSOKey(volumeId, bucketId, currentParentId + 1, "");
    
    LOG.info("Next parent range: volumeId={}, bucketId={}, parentId={}, range=[{} - {}]",
        volumeId, bucketId, currentParentId, startKey, endKey);
    
    return new KeyRange(startKey, endKey);
  }
  
  /**
   * Move to the next bucket for compaction.
   */
  private KeyRange moveToNextBucket() throws IOException {
    // Reset state and find next bucket
    currentParentId = null;
    
    Iterator<Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>>> bucketIter = 
        getMetadataManager().getBucketIterator(buildBucketKey(currentVolumeId, currentBucketId));
    
    if (!bucketIter.hasNext()) {
      LOG.info("No more buckets to process");
      currentVolumeId = null;
      currentBucketId = null;
      return null;
    }
    
    Map.Entry<CacheKey<String>, CacheValue<OmBucketInfo>> bucketEntry = bucketIter.next();
    OmBucketInfo bucketInfo = bucketEntry.getValue().getCacheValue();
    
    currentVolumeId = bucketInfo.getVolumeId();
    currentBucketId = bucketInfo.getObjectID();
    currentParentId = bucketInfo.getObjectID();
    
    String startKey = buildFSOKey(currentVolumeId, currentBucketId, currentParentId, "");
    String endKey = buildFSOKey(currentVolumeId, currentBucketId, currentParentId + 1, "");
    
    return new KeyRange(startKey, endKey);
  }
  
  /**
   * Process a range with potential splitting if it's too large.
   */
  private Pair<KeyRange, KeyRangeStats> processRangeWithSplitting(KeyRange range) throws IOException {
    CompoundKeyRangeStats compoundStats = getCompoundKeyRangeStatsFromRange(range);
    
    if (compoundStats.isEmpty()) {
      LOG.info("Range [{}] has no entries", range);
      return Pair.of(range, new KeyRangeStats());
    }
    
    LOG.info("Range [{}] has {} entries", range, compoundStats.getNumEntries());
    
    // Check if range needs splitting
    if (compoundStats.getNumEntries() <= getMaxCompactionEntries()) {
      return Pair.of(range, compoundStats.getCompoundStats());
    }
    
    // Split range using FSO-aware logic
    LOG.info("Range [{}] exceeds max entries ({} > {}), applying FSO splitting",
        range, compoundStats.getNumEntries(), getMaxCompactionEntries());
    
    List<Pair<KeyRange, KeyRangeStats>> splitRanges = splitRangeByDirectoryBoundaries(
        range, compoundStats, getMaxCompactionEntries());
    
    if (splitRanges.isEmpty()) {
      // Fallback to SST-based splitting
      splitRanges = findFitRanges(compoundStats.getKeyRangeStatsList(), getMaxCompactionEntries());
    }
    
    if (splitRanges.isEmpty()) {
      LOG.warn("Failed to split range [{}], returning original", range);
      return Pair.of(range, compoundStats.getCompoundStats());
    }
    
    // Take the first split range and save resume point
    Pair<KeyRange, KeyRangeStats> firstRange = squashRanges(splitRanges);
    String nextBoundary = findNextParentBoundary(firstRange.getLeft().getEndKey());
    
    if (range.getEndKey().compareTo(nextBoundary) > 0) {
      resumeKey = nextBoundary;
      LOG.info("Split range [{}] into [{}], will resume from {}",
          range, firstRange.getLeft(), resumeKey);
    }
    
    return firstRange;
  }
  
  /**
   * Split a range based on directory boundaries for better FSO alignment.
   */
  private List<Pair<KeyRange, KeyRangeStats>> splitRangeByDirectoryBoundaries(
      KeyRange range, CompoundKeyRangeStats stats, long maxEntries) {
    
    LOG.info("Attempting directory-aware splitting for range [{}]", range);
    List<Pair<KeyRange, KeyRangeStats>> result = new ArrayList<>();
    
    // Group SST files by directory boundaries
    Map<String, List<Pair<KeyRange, KeyRangeStats>>> directoryGroups = new HashMap<>();
    
    for (Pair<KeyRange, KeyRangeStats> sstRange : stats.getKeyRangeStatsList()) {
      String dirBoundary = extractDirectoryBoundary(sstRange.getLeft().getStartKey());
      directoryGroups.computeIfAbsent(dirBoundary, k -> new ArrayList<>()).add(sstRange);
    }
    
    // Build ranges respecting directory boundaries
    long currentEntries = 0;
    List<Pair<KeyRange, KeyRangeStats>> currentGroup = new ArrayList<>();
    
    for (Map.Entry<String, List<Pair<KeyRange, KeyRangeStats>>> entry : directoryGroups.entrySet()) {
      List<Pair<KeyRange, KeyRangeStats>> dirSsts = entry.getValue();
      long dirEntries = dirSsts.stream()
          .mapToLong(p -> p.getRight().getNumEntries())
          .sum();
      
      if (currentEntries > 0 && currentEntries + dirEntries > maxEntries) {
        // Current group is full, save it
        if (!currentGroup.isEmpty()) {
          result.add(squashRanges(currentGroup));
        }
        currentGroup = new ArrayList<>();
        currentEntries = 0;
      }
      
      currentGroup.addAll(dirSsts);
      currentEntries += dirEntries;
    }
    
    // Add remaining group
    if (!currentGroup.isEmpty() && currentEntries <= maxEntries) {
      result.add(squashRanges(currentGroup));
    }
    
    LOG.info("Directory-aware splitting produced {} ranges", result.size());
    return result;
  }
  
  /**
   * Extract directory boundary from an FSO key.
   */
  private String extractDirectoryBoundary(String key) {
    // FSO key format: /volumeId/bucketId/parentId/name
    String[] parts = key.split(OM_KEY_PREFIX);
    if (parts.length >= 4) {
      // Return up to parent ID to group by directory
      return OM_KEY_PREFIX + parts[1] + OM_KEY_PREFIX + parts[2] + OM_KEY_PREFIX + parts[3];
    }
    return key;
  }
  
  /**
   * Find the next parent boundary after the given key.
   */
  private String findNextParentBoundary(String key) {
    // Extract parent ID and increment
    String[] parts = key.split(OM_KEY_PREFIX);
    if (parts.length >= 4) {
      try {
        long parentId = Long.parseLong(parts[3]);
        return OM_KEY_PREFIX + parts[1] + OM_KEY_PREFIX + parts[2] + 
               OM_KEY_PREFIX + (parentId + 1) + OM_KEY_PREFIX;
      } catch (NumberFormatException e) {
        LOG.warn("Failed to parse parent ID from key: {}", key);
      }
    }
    return StringUtils.getKeyPrefixUpperBound(key);
  }
  
  /**
   * Build an FSO key from components.
   */
  private String buildFSOKey(long volumeId, long bucketId, long parentId, String name) {
    return OM_KEY_PREFIX + volumeId + OM_KEY_PREFIX + bucketId + 
           OM_KEY_PREFIX + parentId + OM_KEY_PREFIX + name;
  }
  
  /**
   * Build a bucket key for iteration.
   */
  private String buildBucketKey(long volumeId, long bucketId) {
    return OM_KEY_PREFIX + volumeId + OM_KEY_PREFIX + bucketId;
  }

  
  /**
   * Find ranges that fit within the max entries limit.
   */
  private List<Pair<KeyRange, KeyRangeStats>> findFitRanges(
      List<Pair<KeyRange, KeyRangeStats>> ranges, long maxEntries) {
    
    LOG.info("Finding ranges that fit within {} entries", maxEntries);
    ranges.sort((a, b) -> a.getLeft().getStartKey().compareTo(b.getLeft().getStartKey()));

    List<Pair<KeyRange, KeyRangeStats>> out = new ArrayList<>();
    KeyRangeStats chunkStats = new KeyRangeStats();

    for (Pair<KeyRange, KeyRangeStats> range : ranges) {
      long n = range.getRight().getNumEntries();

      if (n > maxEntries) {
        LOG.warn("Range [{}] exceeds max entries ({} > {}), skipping", 
            range.getLeft(), n, maxEntries);
        continue;
      }

      if (chunkStats.getNumEntries() > 0 && chunkStats.getNumEntries() + n > maxEntries) {
        LOG.info("Current chunk + range would exceed max entries, stopping");
        return out;
      }

      out.add(range);
      chunkStats.add(range.getRight());
    }

    return out;
  }
  
  /**
   * Squash multiple ranges into a single range.
   */
  private Pair<KeyRange, KeyRangeStats> squashRanges(List<Pair<KeyRange, KeyRangeStats>> list) {
    Preconditions.checkNotNull(list, "list is null");
    Preconditions.checkArgument(!list.isEmpty(), "list is empty");

    String minKey = list.get(0).getLeft().getStartKey();
    String maxKey = list.get(0).getLeft().getEndKey();
    KeyRangeStats stats = new KeyRangeStats();
    
    for (Pair<KeyRange, KeyRangeStats> range : list) {
      minKey = StringUtils.min(minKey, range.getLeft().getStartKey());
      maxKey = StringUtils.max(maxKey, range.getLeft().getEndKey());
      stats.add(range.getRight());
    }
    
    return Pair.of(new KeyRange(minKey, maxKey), stats);
  }
  
  /**
   * Get compound statistics for a key range.
   */
  private CompoundKeyRangeStats getCompoundKeyRangeStatsFromRange(KeyRange range) throws IOException {
    LOG.info("Getting compound stats for FSO range [{}]", range);
    
    List<Pair<KeyRange, KeyRangeStats>> keyRangeStatsList = new ArrayList<>();
    List<LiveFileMetaData> liveFileMetaDataList = ((RDBStore)getDBStore()).getDb().getLiveFilesMetaData();
    Map<String, LiveFileMetaData> fileMap = new HashMap<>();
    
    for (LiveFileMetaData metaData : liveFileMetaDataList) {
      fileMap.put(metaData.path() + metaData.fileName(), metaData);
    }

    Map<String, TableProperties> propsMap = getDBStore().getPropertiesOfTableInRange(
        getTableName(), Collections.singletonList(range));

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
      
      // Update directory cache if available
      updateDirectoryCacheWithStats(keyRange, stats);
    }
    
    return new CompoundKeyRangeStats(keyRangeStatsList);
  }
  
  /**
   * Update directory cache with statistics from SST files.
   */
  private void updateDirectoryCacheWithStats(KeyRange range, KeyRangeStats stats) {
    String startKey = range.getStartKey();
    String[] parts = startKey.split(OM_KEY_PREFIX);
    
    if (parts.length >= 4) {
      try {
        long parentId = Long.parseLong(parts[3]);
        DirectoryMetadata dirMeta = directoryCache.get(parentId);
        if (dirMeta != null) {
          dirMeta.entryCount += stats.getNumEntries();
          dirMeta.tombstoneCount += stats.getNumDeletions();
        }
      } catch (NumberFormatException e) {
        // Ignore parsing errors
      }
    }
  }
}
