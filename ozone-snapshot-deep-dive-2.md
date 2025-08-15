# Ozone Snapshot 解析 2 - Snapshot Deleting Service & SST Files Filtering & Snapshot Diff

## 前言

上一篇 [Ozone Snapshot 解析 1 - Snapshot Deep Clean & Reclaimable Key Filter & Snapshot Cache](ozone-snapshot-deep-dive-1.md) 介紹了 Ozone Snapshot 的核心機制，包括 reclaimable key filter 和 snapshot deep clean。本篇將深入探討 SST files filtering 和 snapshot diff 的實作細節。

不得不說 Ozone 真的是把我目前看過唯一一個把 RocksDB 用的最淋漓盡致的系統...

## SST Files Filtering

### 為什麼需要 SST 過濾？

RocksDB 的 SST（Sorted String Table）檔案可能包含其他 bucket 的資料，對於快照來說是冗餘的。

### Implementation

```java
public class SstFilteringService extends BackgroundService {
    
    public void filterSstFiles(SnapshotInfo snapshotInfo) {
        // 1. 開啟快照的 RocksDB 實例
        DBStore snapshotDb = openSnapshotDb(snapshotInfo);
        
        // 2. 建立需要保留的前綴映射
        Map<Integer, List<String>> columnFamilyToPrefixMap = 
            buildPrefixMap(snapshotInfo);
        
        // 3. 執行過濾
        for (Map.Entry<Integer, List<String>> entry : 
                columnFamilyToPrefixMap.entrySet()) {
            
            int columnFamilyId = entry.getKey();
            List<String> prefixes = entry.getValue();
            
            // RocksDB 原生支援的前綴過濾
            snapshotDb.deleteFilesNotMatchingPrefix(
                columnFamilyId, prefixes);
        }
        
        // 4. 標記完成
        createFilteringCompleteMarker(snapshotInfo);
        snapshotInfo.setSstFiltered(true);
    }
}
```

**優化效果**：
- 可減少 90% 以上的儲存空間
- 加速快照的讀取操作
- 不影響資料正確性

## Snapshot Diff

### 基於 Compaction DAG 的差異計算

`RocksDBCheckpointDiffer` 使用 Compaction DAG（有向無環圖）來追蹤 SST 檔案的變化，這是 Ozone 快照 diff 的核心技術：

```java
public class RocksDBCheckpointDiffer {
    
    /**
     * 取得兩個快照之間的 SST 檔案差異列表
     */
    public synchronized Optional<List<String>> getSSTDiffList(
            DifferSnapshotInfo src, DifferSnapshotInfo dest) {
        
        // 讀取兩個快照的 SST 檔案列表
        Set<String> srcSnapFiles = readRocksDBLiveFiles(src.getRocksDB());
        Set<String> destSnapFiles = readRocksDBLiveFiles(dest.getRocksDB());
        
        Set<String> fwdDAGSameFiles = new HashSet<>();
        Set<String> fwdDAGDifferentFiles = new HashSet<>();
        
        // 使用 DAG 遍歷找出差異
        internalGetSSTDiffList(src, dest, srcSnapFiles, destSnapFiles,
            fwdDAGSameFiles, fwdDAGDifferentFiles);
        
        // 過濾只保留相關的 SST 檔案
        if (src.getTablePrefixes() != null && !src.getTablePrefixes().isEmpty()) {
            RocksDiffUtils.filterRelevantSstFiles(fwdDAGDifferentFiles, 
                src.getTablePrefixes(), compactionDag.getCompactionMap(), 
                src.getRocksDB(), dest.getRocksDB());
        }
        
        return Optional.of(new ArrayList<>(fwdDAGDifferentFiles));
    }
    
    /**
     * 核心的 DAG 遍歷邏輯
     */
    synchronized void internalGetSSTDiffList(
            DifferSnapshotInfo src, DifferSnapshotInfo dest,
            Set<String> srcSnapFiles, Set<String> destSnapFiles,
            Set<String> sameFiles, Set<String> differentFiles) {
        
        for (String fileName : srcSnapFiles) {
            // 如果檔案在兩個快照中都存在，則相同
            if (destSnapFiles.contains(fileName)) {
                sameFiles.add(fileName);
                continue;
            }
            
            // 從 DAG 中取得該檔案的 compaction 節點
            CompactionNode infileNode = compactionDag.getCompactionNode(fileName);
            if (infileNode == null) {
                // 檔案從未被 compact，標記為不同
                differentFiles.add(fileName);
                continue;
            }
            
            // 遍歷 DAG 找出該檔案的後繼節點
            Set<CompactionNode> currentLevel = new HashSet<>();
            currentLevel.add(infileNode);
            
            while (!currentLevel.isEmpty()) {
                Set<CompactionNode> nextLevel = new HashSet<>();
                
                for (CompactionNode current : currentLevel) {
                    // 如果達到目標快照的世代，標記為不同
                    if (current.getSnapshotGeneration() < dest.getSnapshotGeneration()) {
                        differentFiles.add(current.getFileName());
                        continue;
                    }
                    
                    // 取得後繼節點
                    Set<CompactionNode> successors = 
                        compactionDag.getForwardCompactionDAG().successors(current);
                    
                    if (successors.isEmpty()) {
                        // 沒有後繼節點，標記為不同
                        differentFiles.add(current.getFileName());
                        continue;
                    }
                    
                    for (CompactionNode nextNode : successors) {
                        if (destSnapFiles.contains(nextNode.getFileName())) {
                            sameFiles.add(nextNode.getFileName());
                        } else {
                            nextLevel.add(nextNode);
                        }
                    }
                }
                currentLevel = nextLevel;
            }
        }
    }
}
```

### Compaction DAG 的維護

`RocksDBCheckpointDiffer` 透過監聽 RocksDB 的 compaction 事件來維護 DAG：

```java
private AbstractEventListener newCompactionCompletedListener() {
    return new AbstractEventListener() {
        @Override
        public void onCompactionCompleted(RocksDB db,
                                        CompactionJobInfo compactionJobInfo) {
            // 記錄 compaction 的輸入和輸出檔案
            long trxId = db.getLatestSequenceNumber();
            
            // 建立 compaction log entry
            CompactionLogEntry compactionLogEntry = new CompactionLogEntry.Builder(
                trxId,
                System.currentTimeMillis(),
                inputFiles,
                outputFiles
            ).build();
            
            // 儲存到 compaction log table
            byte[] key = addToCompactionLogTable(compactionLogEntry);
            
            // 更新 DAG
            compactionDag.populateCompactionDAG(
                compactionLogEntry.getInputFileInfoList(),
                compactionLogEntry.getOutputFileInfoList(),
                compactionLogEntry.getDbSequenceNumber()
            );
            
            // 加入清理佇列
            if (pruneQueue != null) {
                pruneQueue.offer(key);
            }
        }
    };
}
```

### SST 檔案備份與清理

為了支援 diff 計算，系統會備份參與 compaction 的 SST 檔案：

```java
private AbstractEventListener newCompactionBeginListener() {
    return new AbstractEventListener() {
        @Override
        public void onCompactionBegin(RocksDB db,
                                    CompactionJobInfo compactionJobInfo) {
            // 為輸入檔案建立硬連結到備份目錄
            for (String file : compactionJobInfo.inputFiles()) {
                createLink(
                    Paths.get(sstBackupDir, new File(file).getName()),
                    Paths.get(file)
                );
            }
        }
    };
}
```

背景服務定期清理不需要的 SST 檔案：

```java
public void pruneSstFiles() {
    Set<String> nonLeafSstFiles;
    synchronized (this) {
        // 找出 DAG 中的非葉節點（已被 compact 的檔案）
        nonLeafSstFiles = compactionDag.getForwardCompactionDAG().nodes().stream()
            .filter(node -> !compactionDag.getForwardCompactionDAG()
                .successors(node).isEmpty())
            .map(node -> node.getFileName())
            .collect(Collectors.toSet());
    }
    
    // 刪除這些檔案以節省空間
    removeSstFiles(nonLeafSstFiles);
}
```

## Performance Optimization

### 批次處理

```java
// 避免單次事務過大
private static final long MAX_BATCH_SIZE = 32 * 1024 * 1024; // 32MB
private static final int MAX_BATCH_COUNT = 1000;
```

### 並行處理

```java
// 多個 column family 並行過濾
ExecutorService executor = Executors.newFixedThreadPool(numThreads);
List<Future> futures = new ArrayList<>();

for (ColumnFamilyHandle cf : columnFamilies) {
    futures.add(executor.submit(() -> filterColumnFamily(cf)));
}
```

### 延遲載入

```java
// 快照只在需要時才載入到記憶體
public OmKeyInfo getKeyInfo(String keyName) {
    OmSnapshot snapshot = snapshotCache.get(snapshotKey);
    return snapshot.getMetadataManager().getKeyTable().get(keyName);
}
```

## 十一、實戰案例分析

### 11.1 大規模快照管理

在生產環境中，一個 bucket 可能有數百個快照，如何高效管理？

1. **快照鏈優化**：使用跳躍表加速查詢
2. **緩存策略**：LRU + 訪問頻率的混合策略
3. **清理調度**：根據系統負載動態調整清理頻率

### 11.2 快照恢復場景

```java
// 恢復到特定快照
public void restoreFromSnapshot(String snapshotName) {
    // 1. 找到快照
    OmSnapshot snapshot = snapshotManager.getSnapshot(
        volumeName, bucketName, snapshotName);
    
    // 2. 建立恢復任務
    RestoreTask task = new RestoreTask(snapshot, targetBucket);
    
    // 3. 執行恢復（支援斷點續傳）
    task.execute();
}
```

## 十二、未來展望

### 12.1 增量快照

目前的實作已經為增量快照打下基礎：
- 序列號追蹤變更
- Diff 計算能力
- 鏈式結構支援

### 12.2 跨 Region 複製

利用快照實現跨資料中心的資料複製：
- 快照作為複製單元
- 增量傳輸減少頻寬
- 一致性點保證

### 12.3 快照即服務

將快照功能包裝為獨立服務：
- RESTful API
- 定時快照策略
- 自動生命週期管理

## 十三、結語

Apache Ozone 的快照實作展現了現代分散式儲存系統的設計精髓：

1. **效率優先**：O(1) 建立時間、Copy-on-Write、硬連結
2. **可擴展性**：雙鏈結構、批次處理、並行優化
3. **一致性保證**：分散式鎖、事務追蹤、原子操作
4. **資源優化**：SST 過濾、軟引用緩存、延遲載入

這套設計不僅滿足了大規模資料儲存的需求，也為未來的功能擴展預留了空間。通過深入理解這些技術細節，我們可以更好地使用和優化 Ozone，也能將這些設計理念應用到其他系統中。

快照功能看似簡單，但要在分散式環境下做到高效、可靠、可擴展，需要諸多精妙的設計。Ozone 的實作為我們提供了一個優秀的參考範例。