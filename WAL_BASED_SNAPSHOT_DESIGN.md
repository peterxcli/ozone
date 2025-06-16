# Offload Ozone Snapshot to DataNode

## Context and Scope

### Background 

Apache Ozone currently implements snapshots using RocksDB checkpoints stored locally on the Ozone Manager (OM) nodes. While this approach provides fast local access, it faces scalability challenges:

- **Theoretical limit of 65,535 snapshots** due to filesystem constraints
- **Resource consumption on OM nodes** as each snapshot requires local storage
- **Complex key reclamation** requiring scanning through all snapshots

With customers creating tens of thousands of snapshots daily, we propose a new WAL-based snapshot approach that offloads storage to DataNodes while maintaining compatibility with the existing RocksDB checkpoint-based implementation.

### Snapshot Implementation Comparison

| Aspect | RocksDB Checkpoint-Based | WAL-Based (Proposed) |
|--------|-------------------------|---------------------|
| **Pros** | • Faster snapshot key read (local disk IO)<br>• Simpler implementation<br>• Proven stability | • Supports unlimited snapshots<br>• Faster key reclamation<br>• Enables cross-region replication<br>• Offloads storage from OM to DN |
| **Cons** | • Hard limit of 65,535 snapshots<br>• High OM storage consumption<br>• Slow key reclamation | • Additional network IO for reads<br>• Implementation complexity<br>• More failure scenarios |

## Terminologies

- **WAL (Write-Ahead Log)**: RocksDB's transaction log containing all database modifications
- **Sequence Number**: RocksDB's monotonically increasing number identifying each transaction
- **Delta**: Changes between two sequence numbers, extracted from WAL
- **Full Snapshot Image**: Complete RocksDB checkpoint at a specific sequence number
- **Snapshot Chain**: Linked list of snapshots with their sequence numbers and delta references

## Requirements

### Functional Requirements
1. Support creating snapshots without filesystem limitations
2. Maintain compatibility with existing snapshot APIs and semantics
3. Support all existing snapshot operations: create, delete, rename, diff, list
4. Enable efficient key reclamation without scanning all snapshots
5. Support both volume-level and bucket-level snapshots

### Non-Functional Requirements
1. Minimize performance impact on normal key operations
2. Maintain high availability and fault tolerance
3. Support rolling upgrades from existing snapshot implementation
4. Minimize additional operational complexity

## Alternative Designs Considered

### 1. Simple WAL Streaming (Initial Approach)
- Stream WAL entries directly to files in DataNodes
- Use simple B-tree index for sequence numbers
- **Pros**: Simpler implementation, straightforward design
- **Cons**: Linear scan for reconstruction, no optimization for common access patterns, poor performance at scale

### 2. RocksDB Snapshot Shipping
- Ship entire RocksDB snapshots to DataNodes periodically
- **Pros**: Reuses existing checkpoint mechanism
- **Cons**: High storage overhead, slow snapshot creation, limited by network bandwidth

### 3. LSM-Tree Based Approach (Chosen - Inspired by Neon)
- Use LSM-tree structure for indexing WAL deltas
- Implement layer-based storage with delta and image layers
- **Pros**: Write-optimized, space-efficient with compaction, supports millions of snapshots, proven architecture
- **Cons**: Higher implementation complexity, requires background services

## Detailed Design

### Architecture Overview

The WAL-based snapshot system, inspired by Neon's GetPage@LSN architecture, consists of:

1. **DeltaLayerManager**: Manages LSM-tree based delta layer storage and indexing
2. **KeySequenceIndex**: Provides efficient key lookups at any historical sequence number
3. **ImageLayerManager**: Manages periodic full snapshot images for faster reconstruction
4. **SnapshotMetadataTable**: Tracks snapshot metadata including sequence numbers
5. **Modified DeleteKeyTable**: Includes sequence numbers for snapshot-aware deletion
6. **Background Services**: Compaction and image creation services for optimization

### Key Design Principles (from Neon)

1. **Non-Overwriting Storage**: Files are created and deleted but never modified
2. **Immediate Indexing**: WAL records are indexed in memory as they arrive
3. **Layer-Based Organization**: Delta layers and image layers for efficient access
4. **Lazy Reconstruction**: Only reconstruct data when actually accessed
5. **Persistent Data Structures**: Use persistent trees for historical queries

### SnapshotWALStreamingManager

The manager is responsible for continuously streaming WAL data to DataNodes:

```java
public class SnapshotWALStreamingManager {
    private final RDBStore rdbStore;
    private final OzoneManagerProtocol omClient;
    private final SnapshotDeltaTable deltaTable;
    
    // Streaming state
    private long lastStreamedSequence;
    private long targetSequence;
    private final int batchSize = 10000; // Configurable
    
    public void streamWALUpdates() {
        try {
            RDBBatch updates = rdbStore.getUpdatesSince(
                lastStreamedSequence, batchSize);
            
            if (updates.isEmpty()) {
                return;
            }
            
            // Package updates into delta file
            DeltaFile delta = packageDelta(updates);
            
            // Write delta to Ozone
            String deltaPath = writeDeltaToOzone(delta);
            
            // Update metadata
            deltaTable.addDelta(
                lastStreamedSequence,
                delta.getEndSequence(),
                deltaPath
            );
            
            lastStreamedSequence = delta.getEndSequence();
            
        } catch (WALNotAvailableException e) {
            // WAL has been cleaned, need full checkpoint
            handleMissingWAL();
        }
    }
    
    private void handleMissingWAL() {
        // Create full snapshot image at current sequence
        long currentSeq = rdbStore.getLatestSequenceNumber();
        String imagePath = createAndStoreFullImage(currentSeq);
        
        // Update snapshot image table
        snapshotImageTable.addImage(currentSeq, imagePath);
        
        // Reset streaming from this point
        lastStreamedSequence = currentSeq;
    }
}
```

### LSM-Tree Based Index Design (Inspired by Neon)

Following Neon's GetPage@LSN architecture, we implement an LSM-tree-like structure for efficient delta indexing and snapshot reconstruction:

#### Persisting Index in OM's RocksDB

Instead of maintaining separate storage, we leverage OM's existing RocksDB instance to persist all index information:

```java
// New table definitions in OMDBDefinition.java
public static final DBColumnFamilyDefinition<String, DeltaLayerInfo> 
    DELTA_LAYER_TABLE_DEF = new DBColumnFamilyDefinition<>(
        "deltaLayerTable",
        StringCodec.get(),
        DeltaLayerInfo.getCodec());

public static final DBColumnFamilyDefinition<Long, ImageLayerInfo> 
    IMAGE_LAYER_TABLE_DEF = new DBColumnFamilyDefinition<>(
        "imageLayerTable",
        LongCodec.get(),
        ImageLayerInfo.getCodec());

public static final DBColumnFamilyDefinition<String, KeySequenceInfo> 
    KEY_SEQUENCE_TABLE_DEF = new DBColumnFamilyDefinition<>(
        "keySequenceTable",
        StringCodec.get(),
        KeySequenceInfo.getCodec());

public static final DBColumnFamilyDefinition<Long, LayerCoverageInfo> 
    LAYER_COVERAGE_TABLE_DEF = new DBColumnFamilyDefinition<>(
        "layerCoverageTable",
        LongCodec.get(),
        LayerCoverageInfo.getCodec());
```

#### Layer-Based Storage Model with RocksDB Persistence

```java
public class DeltaLayerManager {
    // RocksDB tables for persistent storage
    private final Table<String, DeltaLayerInfo> deltaLayerTable;
    private final Table<Long, LayerCoverageInfo> layerCoverageTable;
    private final Table<String, KeySequenceInfo> keySequenceTable;
    private final OMMetadataManager omMetadataManager;
    
    // In-memory buffer for incoming WAL records
    private final MemoryTable activeMemTable;
    private final long memTableSizeLimit = 1_000_000_000L; // 1GB like Neon
    
    public void processWALRecord(WALRecord record) throws IOException {
        // Index in memory immediately
        activeMemTable.put(
            record.getKey(), 
            record.getSequence(), 
            record.getOperation(),
            record.getValue()
        );
        
        // Also persist key sequence info to RocksDB immediately
        persistKeySequenceInfo(record);
        
        if (activeMemTable.size() > memTableSizeLimit) {
            flushMemTableToDeltaLayer();
        }
    }
    
    private void persistKeySequenceInfo(WALRecord record) throws IOException {
        String keySeqKey = buildKeySequenceKey(record.getKey(), record.getSequence());
        
        KeySequenceInfo seqInfo = KeySequenceInfo.newBuilder()
            .setKey(record.getKey())
            .setSequence(record.getSequence())
            .setOperation(record.getOperation())
            .setTimestamp(Time.now())
            .setValue(record.getValue())
            .build();
        
        // Persist to RocksDB immediately for durability
        keySequenceTable.put(keySeqKey, seqInfo);
    }
    
    private void flushMemTableToDeltaLayer() throws IOException {
        // Create immutable delta layer from memory table
        DeltaLayer layer = createDeltaLayer(activeMemTable);
        
        // Write delta layer to DataNode
        String layerPath = writeDeltaLayerToOzone(layer);
        
        // Persist layer metadata to RocksDB
        DeltaLayerInfo layerInfo = DeltaLayerInfo.newBuilder()
            .setLayerId(layer.getLayerId())
            .setStartSequence(layer.getStartSequence())
            .setEndSequence(layer.getEndSequence())
            .setLayerPath(layerPath)
            .setCreationTime(Time.now())
            .setKeyCount(layer.getKeyCount())
            .setBloomFilter(layer.getSerializedBloomFilter())
            .build();
        
        // Use RocksDB transaction for atomicity
        try (RDBBatchOperation batch = omMetadataManager.getStore()
                .initBatchOperation()) {
            
            // Add to delta layer table
            String layerKey = buildLayerKey(layer.getStartSequence(), 
                                          layer.getEndSequence());
            deltaLayerTable.putWithBatch(batch, layerKey, layerInfo);
            
            // Update coverage index
            updateCoverageIndexInBatch(batch, layer);
            
            // Commit transaction
            omMetadataManager.getStore().commitBatchOperation(batch);
        }
        
        // Clear memory table after successful persistence
        activeMemTable = new MemoryTable();
    }
    
    private void updateCoverageIndexInBatch(RDBBatchOperation batch, 
                                           DeltaLayer layer) throws IOException {
        // Update layer coverage for efficient range queries
        long coverageKey = layer.getStartSequence() / COVERAGE_GRANULARITY;
        
        LayerCoverageInfo coverage = layerCoverageTable.get(coverageKey);
        if (coverage == null) {
            coverage = LayerCoverageInfo.newBuilder()
                .setStartSequence(coverageKey * COVERAGE_GRANULARITY)
                .build();
        }
        
        coverage = coverage.toBuilder()
            .addLayerIds(layer.getLayerId())
            .build();
        
        layerCoverageTable.putWithBatch(batch, coverageKey, coverage);
    }
    
    private String buildKeySequenceKey(String key, long sequence) {
        // Format: "key#sequence" for range scans
        return key + "#" + String.format("%020d", sequence);
    }
    
    private String buildLayerKey(long startSeq, long endSeq) {
        return String.format("%020d-%020d", startSeq, endSeq);
    }
}
```

#### Key-Sequence Index Structure with RocksDB Backend

```java
public class KeySequenceIndex {
    // RocksDB tables for persistent storage
    private final Table<String, KeySequenceInfo> keySequenceTable;
    private final Table<String, DeltaLayerInfo> deltaLayerTable;
    private final Table<Long, ImageLayerInfo> imageLayerTable;
    
    // In-memory cache for hot data (read-through cache backed by RocksDB)
    private final LoadingCache<String, NavigableMap<Long, KeySequenceInfo>> keyCache;
    
    public static class KeySequencePair implements Comparable<KeySequencePair> {
        private final String key;
        private final long sequence;
        
        @Override
        public int compareTo(KeySequencePair other) {
            int keyComp = key.compareTo(other.key);
            return keyComp != 0 ? keyComp : Long.compare(sequence, other.sequence);
        }
    }
    
    public OmKeyInfo getKeyAtSequence(String key, long targetSeq) 
            throws IOException {
        // First check cache
        NavigableMap<Long, KeySequenceInfo> keyHistory = keyCache.get(key);
        
        if (keyHistory.isEmpty()) {
            // If not in cache, load from RocksDB
            keyHistory = loadKeyHistoryFromRocksDB(key);
        }
        
        // Find the latest version before target sequence
        Map.Entry<Long, KeySequenceInfo> entry = 
            keyHistory.floorEntry(targetSeq);
        
        if (entry == null) {
            return null; // Key doesn't exist at this sequence
        }
        
        KeySequenceInfo seqInfo = entry.getValue();
        
        // If it's a delete operation, return null
        if (seqInfo.getOperation() == OperationType.DELETE) {
            return null;
        }
        
        // If we have the full value in the index, return it
        if (seqInfo.hasFullValue()) {
            return seqInfo.getKeyInfo();
        }
        
        // Otherwise, reconstruct from layers
        return reconstructKeyFromLayers(key, seqInfo, targetSeq);
    }
    
    private NavigableMap<Long, KeySequenceInfo> loadKeyHistoryFromRocksDB(
            String key) throws IOException {
        NavigableMap<Long, KeySequenceInfo> history = new TreeMap<>();
        
        // Scan RocksDB for all sequences of this key
        String prefix = key + "#";
        try (TableIterator<String, KeySequenceInfo> iter = 
                keySequenceTable.iterator(prefix)) {
            
            while (iter.hasNext()) {
                Table.KeyValue<String, KeySequenceInfo> kv = iter.next();
                if (!kv.getKey().startsWith(prefix)) {
                    break; // We've moved past this key's entries
                }
                
                KeySequenceInfo info = kv.getValue();
                history.put(info.getSequence(), info);
            }
        }
        
        return history;
    }
    
    // LoadingCache configuration
    private LoadingCache<String, NavigableMap<Long, KeySequenceInfo>> 
            createKeyCache() {
        return CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<String, NavigableMap<Long, KeySequenceInfo>>() {
                @Override
                public NavigableMap<Long, KeySequenceInfo> load(String key) 
                        throws Exception {
                    return loadKeyHistoryFromRocksDB(key);
                }
            });
    }
    
    private List<DeltaLayer> collectRelevantDeltaLayers(
            String key, long fromSeq, long toSeq) {
        List<DeltaLayer> relevant = new ArrayList<>();
        
        // Use layer coverage index for efficient lookup
        LayerCoverage coverage = coverageIndex.getCoverage(fromSeq, toSeq);
        
        for (DeltaLayer layer : coverage.getLayers()) {
            // Use bloom filter for quick filtering
            if (layer.mightContainKey(key)) {
                relevant.add(layer);
            }
        }
        
        return relevant;
    }
}
```

#### Efficient Snapshot Reconstruction

```java
public class SnapshotReconstructor {
    private final KeySequenceIndex keyIndex;
    private final ImageLayerManager imageManager;
    private final DeltaLayerManager deltaManager;
    
    public ReconstructedSnapshot reconstructSnapshot(long targetSequence) {
        // Find nearest image layer
        ImageLayer baseImage = imageManager.findNearestImageBefore(targetSequence);
        
        // Create lazy-loading snapshot view
        return new LazyReconstructedSnapshot(
            baseImage,
            targetSequence,
            keyIndex,
            deltaManager
        );
    }
    
    // Lazy reconstruction - only reconstruct keys when accessed
    private class LazyReconstructedSnapshot implements ReconstructedSnapshot {
        private final ImageLayer baseImage;
        private final long targetSequence;
        private final LoadingCache<String, OmKeyInfo> keyCache;
        
        LazyReconstructedSnapshot(ImageLayer base, long seq, 
                                  KeySequenceIndex index,
                                  DeltaLayerManager deltaManager) {
            this.baseImage = base;
            this.targetSequence = seq;
            this.keyCache = CacheBuilder.newBuilder()
                .maximumSize(10000)
                .build(new CacheLoader<String, OmKeyInfo>() {
                    @Override
                    public OmKeyInfo load(String key) {
                        return index.getKeyAtSequence(key, targetSequence);
                    }
                });
        }
        
        @Override
        public OmKeyInfo lookupKey(String key) {
            return keyCache.getUnchecked(key);
        }
    }
}
```

### Persistence Flow: From WAL to RocksDB

The system ensures all indices are persisted to RocksDB through the following flow:

```java
// 1. WAL Record arrives from RocksDB
WALRecord record = rdbStore.getNextWALRecord();

// 2. Immediately persist to keySequenceTable in RocksDB
String rocksDbKey = "keyName#00000000000000123456"; // key#sequence
KeySequenceInfo info = createKeySequenceInfo(record);
keySequenceTable.put(rocksDbKey, info);

// 3. Also buffer in memory for batching into delta layers
activeMemTable.add(record);

// 4. When memory buffer is full, flush to delta layer
if (activeMemTable.size() > threshold) {
    // Create delta layer file in DataNode
    DeltaLayer layer = createDeltaLayer(activeMemTable);
    String layerPath = writeToDN(layer);
    
    // Persist layer metadata to RocksDB
    deltaLayerTable.put(layerKey, layerInfo);
    layerCoverageTable.put(coverageKey, coverageInfo);
}

// 5. Reading uses RocksDB-backed cache
NavigableMap<Long, KeySequenceInfo> keyHistory = keyCache.get(key);
// Cache loads from RocksDB if not present
```

This ensures:
- **No data loss**: Every WAL record is immediately persisted to RocksDB
- **Efficient reads**: Cache provides fast access while RocksDB provides durability
- **Atomic updates**: All index updates happen within RocksDB transactions

### Why LSM-Tree Based Indexing?

The LSM-tree approach addresses our scalability challenges:

1. **Write Optimization**: LSM-trees are optimized for high write throughput, perfect for continuous WAL streaming
2. **Space Efficiency**: Compaction reduces storage overhead compared to keeping all snapshots
3. **Read Performance**: While reads require merging layers, caching and bloom filters minimize overhead
4. **Scalability**: Can handle millions of snapshots without filesystem limitations

### GetKeysWithPrefix@Seq Implementation

Based on analysis, `GetKeysWithPrefix@Seq` is more suitable than `GetBucket@Seq` because:
1. Aligns with existing key listing patterns in Ozone
2. Supports both bucket-level and volume-level operations
3. More flexible for different use cases

```java
public class SnapshotKeyReader {
    public List<OmKeyInfo> getKeysWithPrefixAtSequence(
            String volumeName, 
            String bucketName,
            String prefix,
            long sequenceNumber) {
        
        // Get reconstructed snapshot at sequence
        ReconstructedSnapshot snapshot = 
            sequenceIndex.getSnapshotAtSequence(sequenceNumber);
        
        // Use existing key iteration logic with prefix filter
        try (TableIterator<String, OmKeyInfo> iter = 
                snapshot.getKeyTable().iterator(prefix)) {
            
            List<OmKeyInfo> keys = new ArrayList<>();
            while (iter.hasNext()) {
                Table.KeyValue<String, OmKeyInfo> kv = iter.next();
                
                // Apply deletion check using sequence number
                if (!isDeletedAtSequence(kv.getKey(), sequenceNumber)) {
                    keys.add(kv.getValue());
                }
            }
            return keys;
        }
    }
    
    private boolean isDeletedAtSequence(String key, long sequence) {
        // Check deleteKeyTable with sequence number
        DeletedKeyInfo deleteInfo = deleteKeyTable.get(key);
        return deleteInfo != null && 
               deleteInfo.getDeleteSequence() <= sequence;
    }
}
```

### Snapshot Creation Flow

```java
public SnapshotInfo createWALBasedSnapshot(
        String volumeName, 
        String bucketName,
        String snapshotName) {
    
    // Get current sequence number
    long sequenceNumber = rdbStore.getLatestSequenceNumber();
    
    // Ensure all WAL records up to this sequence are indexed
    deltaLayerManager.flushUpToSequence(sequenceNumber);
    
    // Create snapshot metadata
    SnapshotInfo snapshotInfo = SnapshotInfo.newBuilder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setName(snapshotName)
        .setCreationTime(Time.now())
        .setSequenceNumber(sequenceNumber)
        .setSnapshotType(SnapshotType.WAL_BASED)
        .build();
    
    // Add to snapshot chain
    snapshotChainManager.addSnapshot(snapshotInfo);
    
    // Schedule background image layer creation if needed
    if (shouldCreateImageLayer(sequenceNumber)) {
        imageLayerService.scheduleImageCreation(sequenceNumber);
    }
    
    return snapshotInfo;
}

private boolean shouldCreateImageLayer(long sequence) {
    // Create image layer if too many delta layers accumulated
    long lastImageSequence = imageManager.getLastImageSequence();
    return (sequence - lastImageSequence) > imageCreationThreshold;
}
```

### Key Reclamation Enhancement

The new design significantly improves key reclamation:

```java
public class EnhancedKeyDeletingService {
    public void reclaimDeletedKeys() {
        // Get minimum sequence number across all snapshots
        long minSnapshotSequence = snapshotChainManager
            .getGlobalSnapshotChain()
            .getMinSequenceNumber();
        
        // Iterate through deleted keys
        try (TableIterator<String, DeletedKeyInfo> iter = 
                deleteKeyTable.iterator()) {
            
            while (iter.hasNext()) {
                DeletedKeyInfo deletedKey = iter.next().getValue();
                
                // Simple sequence number comparison
                if (deletedKey.getDeleteSequence() < minSnapshotSequence) {
                    // Safe to reclaim - no snapshot can see this key
                    performKeyReclamation(deletedKey);
                    deleteKeyTable.delete(deletedKey.getKey());
                }
            }
        }
    }
}
```

### Snapshot Diff with WAL

Snapshot diff becomes more efficient by replaying only the deltas:

```java
public SnapshotDiffReport computeWALBasedDiff(
        SnapshotInfo fromSnapshot,
        SnapshotInfo toSnapshot) {
    
    long fromSeq = fromSnapshot.getSequenceNumber();
    long toSeq = toSnapshot.getSequenceNumber();
    
    // Get deltas in range
    List<DeltaEntry> deltas = deltaTable.getDeltasInRange(fromSeq, toSeq);
    
    SnapshotDiffReport report = new SnapshotDiffReport();
    
    // Process each delta
    for (DeltaEntry delta : deltas) {
        DeltaFile deltaFile = readDeltaFromOzone(delta.getPath());
        
        for (DeltaRecord record : deltaFile.getRecords()) {
            if (record.getSequence() > fromSeq && 
                record.getSequence() <= toSeq) {
                
                // Add to diff report based on operation type
                processDeltaRecord(record, report);
            }
        }
    }
    
    return report;
}
```

## Limitations

1. **Network Dependency**: Snapshot operations require network IO to DataNodes
2. **WAL Retention**: Requires careful tuning of RocksDB WAL retention settings
3. **Storage Overhead**: Stores both deltas and periodic full images
4. **Reconstruction Latency**: First access to old snapshots may be slower

## Cross-cutting Concerns

### Security
- Reuse existing Ozone security model for delta/image files
- Ensure proper ACLs on snapshot data stored in DataNodes
- Audit logging for all snapshot operations

### Observability

Key metrics to monitor:
- `om_wal_streaming_lag`: Lag between current sequence and last streamed
- `om_snapshot_reconstruction_time`: Time to reconstruct snapshots
- `om_delta_files_count`: Number of delta files in the system
- `om_snapshot_fallback_count`: Number of times full checkpoint was needed

### Compatibility (Rolling Upgrade)

The design supports rolling upgrades:

1. **Phase 1**: Deploy new code with WAL-based snapshot disabled
2. **Phase 2**: Enable WAL streaming in background (no new snapshots yet)
3. **Phase 3**: Allow new WAL-based snapshots while maintaining old ones
4. **Phase 4**: Optionally migrate old snapshots to new format

```java
public class SnapshotCompatibilityManager {
    public SnapshotReader getSnapshotReader(SnapshotInfo info) {
        switch (info.getSnapshotType()) {
            case CHECKPOINT_BASED:
                return new CheckpointSnapshotReader(info);
            case WAL_BASED:
                return new WALSnapshotReader(info);
            default:
                throw new IllegalArgumentException(
                    "Unknown snapshot type: " + info.getSnapshotType());
        }
    }
}
```

## Table Initialization

New tables need to be registered in `OmMetadataManagerImpl`:

```java
private void initializeWALSnapshotTables() throws IOException {
    // Delta layer index
    deltaLayerTable = this.store.getTable(DELTA_LAYER_TABLE_DEF);
    tables.add(deltaLayerTable);
    
    // Image layer index
    imageLayerTable = this.store.getTable(IMAGE_LAYER_TABLE_DEF);
    tables.add(imageLayerTable);
    
    // Key sequence index
    keySequenceTable = this.store.getTable(KEY_SEQUENCE_TABLE_DEF);
    tables.add(keySequenceTable);
    
    // Layer coverage index
    layerCoverageTable = this.store.getTable(LAYER_COVERAGE_TABLE_DEF);
    tables.add(layerCoverageTable);
    
    // Enhanced deleted key table with sequence numbers
    if (enableWALBasedSnapshots) {
        deletedTable = this.store.getTable(DELETED_TABLE_V2_DEF);
    } else {
        deletedTable = this.store.getTable(DELETED_TABLE_DEF);
    }
    tables.add(deletedTable);
}
```

## Configuration Parameters

```xml
<!-- ozone-site.xml -->
<property>
  <name>ozone.om.snapshot.type</name>
  <value>CHECKPOINT_BASED</value>
  <description>
    Snapshot implementation type: CHECKPOINT_BASED or WAL_BASED
  </description>
</property>

<property>
  <name>ozone.om.wal.streaming.batch.size</name>
  <value>10000</value>
  <description>
    Number of WAL entries to batch before creating delta file
  </description>
</property>

<property>
  <name>ozone.om.wal.streaming.interval.ms</name>
  <value>60000</value>
  <description>
    Interval for WAL streaming manager to check for new entries
  </description>
</property>

<property>
  <name>ozone.om.snapshot.full.image.interval</name>
  <value>1000000</value>
  <description>
    Create full snapshot image after this many WAL entries
  </description>
</property>
```

### Modified DeleteKeyTable Structure

```java
// Enhanced DeletedKeyInfo to include sequence number
public class DeletedKeyInfo {
    private String keyName;
    private long deletionTime;
    private long deleteSequenceNumber;  // NEW: RocksDB sequence at deletion
    private List<OmKeyLocationInfoGroup> keyLocationList;
    // ... other fields
}

// Table definition
public static final DBColumnFamilyDefinition<String, DeletedKeyInfo> 
    DELETED_TABLE_V2_DEF = new DBColumnFamilyDefinition<>(
        "deletedTableV2",
        StringCodec.get(),
        DeletedKeyInfo.getCodecV2());  // New codec with sequence number
```

## Background Services

### Compaction Service

Similar to Neon's approach, we implement background compaction to optimize storage:

```java
public class DeltaCompactionService {
    public void compactDeltaLayers() {
        // Find candidate layers for compaction
        List<DeltaLayer> candidates = deltaManager.getCompactionCandidates();
        
        if (candidates.size() < minLayersForCompaction) {
            return;
        }
        
        // Merge multiple delta layers into one
        DeltaLayer compactedLayer = mergeDeltaLayers(candidates);
        
        // Write compacted layer
        String compactedPath = writeCompactedLayer(compactedLayer);
        
        // Atomic replacement in LSM tree
        deltaManager.replaceLayersWithCompacted(
            candidates, compactedLayer, compactedPath);
        
        // Clean up old layers
        cleanupOldLayers(candidates);
    }
}
```

### Image Layer Creation Service

```java
public class ImageLayerService {
    public void createImageLayer(long targetSequence) {
        // Reconstruct full state at target sequence
        Map<String, OmKeyInfo> fullState = reconstructFullState(targetSequence);
        
        // Create image layer
        ImageLayer imageLayer = new ImageLayer(
            targetSequence,
            fullState,
            createBloomFilter(fullState.keySet())
        );
        
        // Write to DataNodes
        String imagePath = writeImageLayerToOzone(imageLayer);
        
        // Update image layer index
        imageManager.addImageLayer(targetSequence, imagePath);
        
        // Schedule cleanup of old delta layers
        scheduleOldDeltaCleanup(targetSequence);
    }
}
```

## Performance Optimizations

### 1. Parallel Layer Processing
```java
public class ParallelReconstructor {
    private final ExecutorService executor = 
        Executors.newFixedThreadPool(numThreads);
    
    public Map<String, OmKeyInfo> reconstructKeysInParallel(
            List<String> keys, long targetSeq) {
        
        List<Future<KeyValue>> futures = keys.stream()
            .map(key -> executor.submit(() -> {
                OmKeyInfo value = keyIndex.getKeyAtSequence(key, targetSeq);
                return new KeyValue(key, value);
            }))
            .collect(Collectors.toList());
        
        return futures.stream()
            .map(this::getFutureValue)
            .collect(Collectors.toMap(
                KeyValue::getKey, 
                KeyValue::getValue));
    }
}
```

### 2. Read-Optimized Delta Format
```java
public class OptimizedDeltaLayer {
    // Column-oriented storage for better compression
    private final ColumnStore<String> keys;
    private final ColumnStore<Long> sequences;
 n    private final ColumnStore<OperationType> operations;
    private final ColumnStore<byte[]> values;
    
    // Secondary indexes for common access patterns
    private final Map<String, List<Integer>> keyToRowIndex;
    private final NavigableMap<Long, List<Integer>> sequenceToRowIndex;
}
```

## Future Enhancements

1. **Multi-Version Concurrency Control (MVCC)**: Full MVCC support using sequence numbers
2. **Cross-Region Replication**: Leverage delta layers for efficient replication
3. **Branching Support**: Create snapshot branches by forking at specific sequences
4. **Time-Travel Queries**: Support SQL-like queries at any historical point
5. **Incremental Backup**: Use delta layers for efficient incremental backups

## Key Design Decision: Using OM's RocksDB for Index Persistence

Instead of creating a separate storage system, we leverage OM's existing RocksDB instance to persist all index information. This provides several advantages:

### Benefits of RocksDB Persistence

1. **Atomicity**: Use RocksDB transactions to ensure index updates are atomic with metadata changes
2. **Durability**: Indexes are automatically included in OM's existing backup/restore procedures
3. **Performance**: RocksDB's LSM-tree structure is already optimized for our use case
4. **Simplicity**: No need to manage separate index storage or recovery mechanisms
5. **Consistency**: Index state is always consistent with OM metadata state

### Index Tables Structure

| Table Name | Key Format | Value | Purpose |
|------------|------------|-------|---------|
| deltaLayerTable | `startSeq-endSeq` | DeltaLayerInfo | Maps sequence ranges to delta files |
| imageLayerTable | `sequence` (Long) | ImageLayerInfo | Tracks full snapshot images |
| keySequenceTable | `key#sequence` | KeySequenceInfo | Per-key sequence history |
| layerCoverageTable | `coverage_bucket` (Long) | LayerCoverageInfo | Efficient range queries |

### Integration with Existing Tables

- **Enhanced DeleteKeyTable**: Adds sequence number to track when keys were deleted
- **SnapshotInfoTable**: Extended to include WAL-based snapshot metadata
- **Transactional Updates**: All index updates happen within same RocksDB transaction as metadata updates

## Conclusion

This design addresses the original concern about efficient snapshot reconstruction by adopting Neon's LSM-tree based approach and leveraging OM's RocksDB for persistence. The key innovations are:

1. **LSM-Tree Indexing**: Treating snapshot data as a time-series database with efficient indexing
2. **RocksDB Integration**: Using existing storage infrastructure for index persistence
3. **Layer-Based Storage**: Separating delta and image layers for optimal performance
4. **Lazy Reconstruction**: Only reconstructing data when accessed

The result is a system that can support millions of snapshots without filesystem limitations while maintaining reasonable performance and operational simplicity. By using OM's RocksDB, we ensure consistency, durability, and atomicity without additional complexity.