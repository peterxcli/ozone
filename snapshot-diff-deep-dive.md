# Apache Ozone Snapshot Diff 深度解析

## Introduction

Snapshot Diff 是 Apache Ozone Snapshot 功能中最重要的組件之一。它解決了在 LSM-Tree 架構下，如何在 RocksDB Compaction 不斷改變 SST 文件的情況下，精確追蹤並計算任意兩個 Snapshot 之間的變更。

## 要解決什麼問題？

### 核心問題
在分布式存儲系統中，用戶需要知道兩個時間點之間的數據變化，這對以下場景至關重要：

1. **增量備份與複製**：只傳輸變化的數據，大幅降低網絡和存儲成本
2. **數據同步**：在災難恢復場景中，快速識別需要同步的數據
3. **合規審計**：追蹤數據變更歷史，滿足法規要求
4. **版本控制**：理解數據演化過程，支持回滾和分析

### 技術挑戰

最大的挑戰來自 RocksDB 的 Compaction 機制：

```
原始想法：
Time T1: SST files = {A, B, C}
Time T2: SST files = {A, B, C, D, E}
Diff = {D, E} ✓ 簡單！

實際情況（有 Compaction）：
Time T1: SST files = {A, B, C}
Compaction: A + B → F
Time T2: SST files = {F, C, D, E}
Diff = ? 🤔 無法簡單比較！
```

## 這個問題為什麼值得解決？

### 業務價值

1. **成本節約**：
   - 減少 90%+ 的數據傳輸量（相比全量複製）
   - 降低存儲成本（只存儲增量變化）
   - 減少計算資源消耗

2. **效率提升**：
   - 分鐘級增量同步 vs 小時級全量同步
   - 支持更頻繁的備份策略
   - 實現近實時的數據同步

3. **新功能支持**：
   - 支持 DistCp with SnapshotDiff
   - 實現跨集群數據同步
   - 構建變更數據捕獲（CDC）系統

### 技術價值

1. **保持性能**：不能因為 Snapshot Diff 而關閉 Compaction
2. **可擴展性**：支持 PB 級數據的快速 Diff
3. **可靠性**：即使部分歷史丟失，也能提供降級服務

## 有哪些做法？

### 方案一：禁用 Compaction（❌ 不可行）

**優點**：
- 實現簡單，直接比較 SST 文件列表
- 無需額外的元數據存儲

**缺點**：
- 讀性能嚴重下降（需要掃描大量 SST 文件）
- 空間放大嚴重（無法清理刪除的數據）
- 違背 LSM-Tree 的設計初衷

### 方案二：全量掃描對比（Fallback 方案）

**實現**：
```java
// 偽代碼
Set<Key> keysInSnapshot1 = scanAllKeys(snapshot1);
Set<Key> keysInSnapshot2 = scanAllKeys(snapshot2);
Set<Key> added = keysInSnapshot2 - keysInSnapshot1;
Set<Key> deleted = keysInSnapshot1 - keysInSnapshot2;
// 檢查修改的 keys...
```

**優點**：
- 結果 100% 準確
- 不依賴任何歷史信息
- 實現相對簡單

**缺點**：
- 性能差：O(n) 複雜度，n 是總 key 數
- 資源消耗大：需要大量內存和 CPU
- 無法支持大規模數據集

### 方案三：Compaction DAG 追蹤（✅ Ozone 採用方案）

**核心思想**：構建 SST 文件的 Compaction 歷史 DAG（有向無環圖）

```
Compaction DAG 示例：
    A ──┐
        ├──> D ──┐
    B ──┘        ├──> F
                 │
    C ──────────┘
    
    E （新增文件）
```

**實現架構**：

```
┌─────────────────────────────────────────────┐
│           SnapshotDiffManager               │
│  ┌─────────────────────────────────────┐   │
│  │        Job Queue & Scheduling       │   │
│  └─────────────────────────────────────┘   │
│                    │                        │
│  ┌─────────────────┴─────────────────┐     │
│  │    RocksDBCheckpointDiffer        │     │
│  │  ┌─────────────────────────────┐  │     │
│  │  │  Compaction Event Listener  │  │     │
│  │  └─────────────────────────────┘  │     │
│  │  ┌─────────────────────────────┐  │     │
│  │  │      DAG Construction       │  │     │
│  │  └─────────────────────────────┘  │     │
│  │  ┌─────────────────────────────┐  │     │
│  │  │   SST File Management      │  │     │
│  │  └─────────────────────────────┘  │     │
│  └───────────────────────────────────┘     │
└─────────────────────────────────────────────┘
```

**優點**：
- 高效：只需讀取變化的 SST 文件，O(delta) 複雜度
- 準確：通過 DAG 精確還原歷史變化
- 可配置：支持歷史保留期限設置

**缺點**：
- 實現複雜：需要精心設計 DAG 結構和遍歷算法
- 額外存儲：需要保存 Compaction 歷史和 SST 文件
- 歷史限制：超過保留期的 Diff 需要 Fallback

## 社群最後的考量

### 1. 混合策略

Ozone 社群最終採用了智能混合策略：

```java
if (compactionHistoryAvailable && withinRetentionPeriod) {
    // 快速路徑：使用 DAG
    return calculateDiffUsingDAG();
} else {
    // 降級路徑：全量掃描
    return calculateFullDiff();
}
```

### 2. 關鍵設計決策

1. **可配置的歷史保留期**：
   - 默認 30 天
   - 用戶可根據需求調整
   - 自動清理過期數據

2. **SST 文件管理**：
   - 使用硬鏈接保留 SST 文件
   - 支持值裁剪以節省空間
   - 智能垃圾回收機制

3. **性能限制**：
   - 單次 Diff 的最大 key 數限制
   - 並發 Diff 任務數限制
   - 支持分頁返回結果

4. **Native Library 支持**：
   - 優先使用原生庫讀取 Tombstone
   - 支持降級到純 Java 實現
   - 自動檢測和切換

### 3. 實現細節

**Compaction 事件監聽**：
```java
// 註冊到 RocksDB 的事件監聽器
@Override
public void onCompactionBegin(RocksDB db, CompactionJobInfo info) {
    // 1. 記錄輸入 SST 文件
    // 2. 創建硬鏈接保留文件
    // 3. 更新 DAG 結構
}

@Override
public void onCompactionCompleted(RocksDB db, CompactionJobInfo info) {
    // 1. 記錄輸出 SST 文件
    // 2. 建立輸入->輸出映射
    // 3. 持久化 Compaction 記錄
}
```

**DAG 遍歷算法**：
```java
// 找出兩個 Snapshot 之間的增量 SST 文件
Set<String> getDeltaSSTFiles(Snapshot from, Snapshot to) {
    Set<String> fromSSTs = getSSTFilesAtTime(from.getCreationTime());
    Set<String> toSSTs = getSSTFilesAtTime(to.getCreationTime());
    
    // 使用 DAG 追蹤哪些文件被 Compact
    Set<String> compactedAway = traceCompactedFiles(fromSSTs, toSSTs);
    Set<String> deltaFiles = new HashSet<>(toSSTs);
    deltaFiles.removeAll(fromSSTs);
    deltaFiles.addAll(findSourceFiles(compactedAway));
    
    return deltaFiles;
}
```

## 總結

Apache Ozone 的 Snapshot Diff 實現展示了如何在複雜的分布式系統中平衡性能、準確性和可靠性：

1. **問題本質**：在 LSM-Tree 架構下實現高效的增量變更追蹤
2. **核心創新**：Compaction DAG 追蹤機制
3. **工程智慧**：混合策略確保系統的健壯性
4. **實用導向**：充分考慮生產環境的各種場景

這個設計不僅解決了技術難題，更為 Ozone 生態系統帶來了豐富的應用場景，如增量複製、數據同步、審計追蹤等功能。

## 參考資源

- [HDDS-9517: Snapshot diff between any two snapshots](https://issues.apache.org/jira/browse/HDDS-9517)
- [Design Document: Snapshot Diff](https://cwiki.apache.org/confluence/display/OZONE/Snapshot+diff+between+any+two+snapshots)
- RocksDB Compaction 文檔
- Apache Ozone 源碼：`hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/snapshot/`