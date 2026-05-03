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

package org.apache.hadoop.ozone.om.snapshot.filter;

import static org.apache.hadoop.ozone.OzoneConsts.OM_KEY_PREFIX;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_SNAPSHOT_KEY_RECLAIM_INTERVAL_ENABLED;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_SNAPSHOT_KEY_RECLAIM_INTERVAL_ENABLED_DEFAULT;
import static org.apache.hadoop.ozone.om.helpers.SnapshotInfo.SnapshotStatus.SNAPSHOT_ACTIVE;
import static org.apache.hadoop.ozone.om.snapshot.SnapshotUtils.isBlockLocationInfoSame;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.utils.TransactionInfo;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.om.DeletingServiceMetrics;
import org.apache.hadoop.ozone.om.KeyManager;
import org.apache.hadoop.ozone.om.OmSnapshot;
import org.apache.hadoop.ozone.om.OmSnapshotManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.SnapshotChainInfo;
import org.apache.hadoop.ozone.om.SnapshotChainManager;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.SnapshotInfo;
import org.apache.hadoop.ozone.om.lock.IOzoneManagerLock;
import org.apache.hadoop.ozone.om.snapshot.SnapshotUtils;
import org.apache.ratis.util.MemoizedCheckedSupplier;
import org.apache.ratis.util.function.CheckedSupplier;
import org.apache.ratis.util.function.UncheckedAutoCloseableSupplier;

/**
 * Filter to return deleted keys which are reclaimable based on their presence in previous snapshot in
 * the snapshot chain.
 */
public class ReclaimableKeyFilter extends ReclaimableFilter<OmKeyInfo> {
  private static final ActiveSnapshotSequences EMPTY_SEQUENCES =
      new ActiveSnapshotSequences(new long[0], new UUID[0]);

  private final Map<UUID, Long> exclusiveSizeMap;
  private final Map<UUID, Long> exclusiveReplicatedSizeMap;
  private final SnapshotChainManager snapshotChainManager;
  private final SnapshotInfo currentSnapshotInfo;
  private final boolean seqNumIntervalEnabled;
  private final Map<String, Optional<ActiveSnapshotSequences>> activeSnapshotSequencesByPath;

  /**
   * @param currentSnapshotInfo  : If null the deleted keys in AOS needs to be processed, hence the latest snapshot
   *                             in the snapshot chain corresponding to bucket key needs to be processed.
   * @param keyManager      : keyManager corresponding to snapshot or AOS.
   * @param lock                 : Lock for Active OM.
   */
  public ReclaimableKeyFilter(OzoneManager ozoneManager,
                              OmSnapshotManager omSnapshotManager, SnapshotChainManager snapshotChainManager,
                              SnapshotInfo currentSnapshotInfo, KeyManager keyManager,
                              IOzoneManagerLock lock) {
    super(ozoneManager, omSnapshotManager, snapshotChainManager, currentSnapshotInfo, keyManager, lock, 2);
    this.exclusiveSizeMap = new HashMap<>();
    this.exclusiveReplicatedSizeMap = new HashMap<>();
    this.snapshotChainManager = snapshotChainManager;
    this.currentSnapshotInfo = currentSnapshotInfo;
    OzoneConfiguration configuration = ozoneManager.getConfiguration();
    this.seqNumIntervalEnabled = configuration == null ||
        configuration.getBoolean(OZONE_OM_SNAPSHOT_KEY_RECLAIM_INTERVAL_ENABLED,
            OZONE_OM_SNAPSHOT_KEY_RECLAIM_INTERVAL_ENABLED_DEFAULT);
    this.activeSnapshotSequencesByPath = new HashMap<>();
  }

  @Override
  protected String getVolumeName(Table.KeyValue<String, OmKeyInfo> keyValue) throws IOException {
    return keyValue.getValue().getVolumeName();
  }

  @Override
  protected String getBucketName(Table.KeyValue<String, OmKeyInfo> keyValue) throws IOException {
    return keyValue.getValue().getBucketName();
  }

  @Override
  public synchronized Boolean apply(Table.KeyValue<String, OmKeyInfo> keyValue) throws IOException {
    Optional<Boolean> seqNumDecision = applySeqNumInterval(keyValue);
    if (seqNumDecision.isPresent()) {
      incrementSeqNumIntervalOptimized();
      return seqNumDecision.get();
    }
    incrementSeqNumIntervalFallback();
    return super.apply(keyValue);
  }

  @Override
  /**
   * Determines whether a deleted key entry is reclaimable by checking its presence in prior snapshots.
   *
   * This method validates the existence of the deleted key in the previous snapshot's key table or file table.
   * If the key is not found in the previous snapshot, it is marked as reclaimable. Otherwise, additional checks
   * are performed using the previous-to-previous snapshot to confirm if the key is exclusively present in the
   * previous snapshot and accounted in the previous snapshot's exclusive size.
   *
   * @param deletedKeyInfo The key-value pair representing the deleted key information.
   * @return {@code true} if the key is reclaimable (not present in prior snapshots), {@code false} otherwise.
   * @throws IOException If an error occurs while accessing snapshot data or key information.
   */
  protected Boolean isReclaimable(Table.KeyValue<String, OmKeyInfo> deletedKeyInfo) throws IOException {
    UncheckedAutoCloseableSupplier<OmSnapshot> previousSnapshot = getPreviousOmSnapshot(1);


    KeyManager previousKeyManager = Optional.ofNullable(previousSnapshot)
        .map(i -> i.get().getKeyManager()).orElse(null);


    // Getting keyInfo from prev snapshot's keyTable/fileTable
    CheckedSupplier<Optional<OmKeyInfo>, IOException> previousKeyInfo =
        MemoizedCheckedSupplier.valueOf(() -> getPreviousSnapshotKeyInfo(getVolumeId(), getBucketInfo(),
            deletedKeyInfo.getValue(), getKeyManager(), previousKeyManager));
    // If file not present in previous snapshot then it won't be present in previous to previous snapshot either.
    if (!previousKeyInfo.get().isPresent()) {
      return true;
    }

    UncheckedAutoCloseableSupplier<OmSnapshot> previousToPreviousSnapshot = getPreviousOmSnapshot(0);
    KeyManager previousToPreviousKeyManager = Optional.ofNullable(previousToPreviousSnapshot)
        .map(i -> i.get().getKeyManager()).orElse(null);

    // Getting keyInfo from prev to prev snapshot's keyTable/fileTable based on keyInfo of prev keyTable
    CheckedSupplier<Optional<OmKeyInfo>, IOException> previousPrevKeyInfo =
        MemoizedCheckedSupplier.valueOf(() -> getPreviousSnapshotKeyInfo(
            getVolumeId(), getBucketInfo(), previousKeyInfo.get().orElse(null), previousKeyManager,
            previousToPreviousKeyManager));
    SnapshotInfo previousSnapshotInfo = getPreviousSnapshotInfo(1);
    calculateExclusiveSize(previousSnapshotInfo, previousKeyInfo, previousPrevKeyInfo,
        exclusiveSizeMap, exclusiveReplicatedSizeMap);
    return false;
  }

  public Map<UUID, Long> getExclusiveSizeMap() {
    return exclusiveSizeMap;
  }

  public Map<UUID, Long> getExclusiveReplicatedSizeMap() {
    return exclusiveReplicatedSizeMap;
  }

  private Optional<Boolean> applySeqNumInterval(Table.KeyValue<String, OmKeyInfo> keyValue) throws IOException {
    if (!seqNumIntervalEnabled) {
      return Optional.empty();
    }

    OmKeyInfo keyInfo = keyValue.getValue();
    if (!keyInfo.hasSeqNumMin() || !keyInfo.hasSeqNumMax()) {
      return Optional.empty();
    }

    String volume = getVolumeName(keyValue);
    String bucket = getBucketName(keyValue);
    validateCurrentSnapshotPath(volume, bucket);

    long seqNumMin = keyInfo.getSeqNumMin();
    long seqNumMax = keyInfo.getSeqNumMax();
    if (seqNumMin >= seqNumMax) {
      return Optional.of(true);
    }

    Optional<ActiveSnapshotSequences> activeSnapshots = getActiveSnapshotSequences(volume, bucket);
    if (!activeSnapshots.isPresent()) {
      return Optional.empty();
    }

    ActiveSnapshotSequences snapshotSequences = activeSnapshots.get();
    int pos = lowerBound(snapshotSequences.getCreateSeqNums(), seqNumMin);
    boolean referenced = pos < snapshotSequences.getCreateSeqNums().length &&
        snapshotSequences.getCreateSeqNums()[pos] < seqNumMax;
    if (referenced) {
      addExclusiveSize(snapshotSequences.getSnapshotIds()[pos], keyInfo);
    }
    return Optional.of(!referenced);
  }

  private void validateCurrentSnapshotPath(String volume, String bucket) throws IOException {
    if (currentSnapshotInfo != null &&
        (!currentSnapshotInfo.getVolumeName().equals(volume) ||
            !currentSnapshotInfo.getBucketName().equals(bucket))) {
      throw new IOException("Volume and Bucket name for snapshot : " + currentSnapshotInfo + " do not match " +
          "against the volume: " + volume + " and bucket: " + bucket + " of the key.");
    }
  }

  private Optional<ActiveSnapshotSequences> getActiveSnapshotSequences(String volume, String bucket)
      throws IOException {
    String snapshotPath = volume + OM_KEY_PREFIX + bucket;
    Optional<ActiveSnapshotSequences> cached = activeSnapshotSequencesByPath.get(snapshotPath);
    if (cached != null) {
      return cached;
    }

    Optional<ActiveSnapshotSequences> activeSnapshots = buildActiveSnapshotSequences(snapshotPath);
    activeSnapshotSequencesByPath.put(snapshotPath, activeSnapshots);
    return activeSnapshots;
  }

  private Optional<ActiveSnapshotSequences> buildActiveSnapshotSequences(String snapshotPath) throws IOException {
    LinkedHashMap<UUID, SnapshotChainInfo> snapshotChainPath =
        snapshotChainManager.getSnapshotChainPath(snapshotPath);
    if (snapshotChainPath == null || snapshotChainPath.isEmpty()) {
      return Optional.of(EMPTY_SEQUENCES);
    }

    List<SnapshotSeqInfo> seqInfos = new ArrayList<>();
    for (UUID snapshotId : snapshotChainPath.keySet()) {
      SnapshotInfo snapshotInfo = SnapshotUtils.getSnapshotInfo(getOzoneManager(), snapshotChainManager, snapshotId);
      if (snapshotInfo.getSnapshotStatus() != SNAPSHOT_ACTIVE) {
        continue;
      }
      ByteString createTransactionInfo = snapshotInfo.getCreateTransactionInfo();
      if (createTransactionInfo == null || createTransactionInfo.isEmpty()) {
        return Optional.empty();
      }
      long createSeqNum = TransactionInfo.fromByteString(createTransactionInfo).getTransactionIndex();
      seqInfos.add(new SnapshotSeqInfo(createSeqNum, snapshotId));
    }
    if (seqInfos.isEmpty()) {
      return Optional.of(EMPTY_SEQUENCES);
    }

    seqInfos.sort(Comparator.comparingLong(SnapshotSeqInfo::getCreateSeqNum));
    long[] createSeqNums = new long[seqInfos.size()];
    UUID[] snapshotIds = new UUID[seqInfos.size()];
    for (int i = 0; i < seqInfos.size(); i++) {
      SnapshotSeqInfo seqInfo = seqInfos.get(i);
      createSeqNums[i] = seqInfo.getCreateSeqNum();
      snapshotIds[i] = seqInfo.getSnapshotId();
    }
    return Optional.of(new ActiveSnapshotSequences(createSeqNums, snapshotIds));
  }

  private static int lowerBound(long[] values, long target) {
    int low = 0;
    int high = values.length;
    while (low < high) {
      int mid = low + ((high - low) >>> 1);
      if (values[mid] < target) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  private void addExclusiveSize(UUID snapshotId, OmKeyInfo keyInfo) {
    exclusiveSizeMap.compute(snapshotId,
        (k, v) -> (v == null ? 0 : v) + keyInfo.getDataSize());
    exclusiveReplicatedSizeMap.compute(snapshotId,
        (k, v) -> (v == null ? 0 : v) + keyInfo.getReplicatedSize());
  }

  private void incrementSeqNumIntervalOptimized() {
    DeletingServiceMetrics metrics = getOzoneManager().getDeletionMetrics();
    if (metrics != null) {
      metrics.incrSnapKeyReclaimIntervalOptimized();
    }
  }

  private void incrementSeqNumIntervalFallback() {
    DeletingServiceMetrics metrics = getOzoneManager().getDeletionMetrics();
    if (metrics != null) {
      metrics.incrSnapKeyReclaimIntervalFallback();
    }
  }

  /**
   * To calculate Exclusive Size for current snapshot, Check
   * the next snapshot deletedTable if the deleted key is
   * referenced in current snapshot and not referenced in the
   * previous snapshot then that key is exclusive to the current
   * snapshot. Here since we are only iterating through
   * deletedTable we can check the previous and previous to
   * previous snapshot to achieve the same.
   * previousSnapshot - Snapshot for which exclusive size is
   *                    getting calculating.
   * currSnapshot - Snapshot's deletedTable is used to calculate
   *                previousSnapshot snapshot's exclusive size.
   * previousToPrevSnapshot - Snapshot which is used to check
   *                 if key is exclusive to previousSnapshot.
   */
  private void calculateExclusiveSize(SnapshotInfo previousSnapshotInfo,
                                      CheckedSupplier<Optional<OmKeyInfo>, IOException> keyInfoPrevSnapshot,
                                      CheckedSupplier<Optional<OmKeyInfo>, IOException> keyInfoPrevToPrevSnapshot,
                                      Map<UUID, Long> exclusiveSizes, Map<UUID, Long> exclusiveReplicatedSizes)
      throws IOException {
    if (keyInfoPrevSnapshot.get().isPresent() && !keyInfoPrevToPrevSnapshot.get().isPresent()) {
      OmKeyInfo keyInfo = keyInfoPrevSnapshot.get().get();
      exclusiveSizes.compute(previousSnapshotInfo.getSnapshotId(),
          (k, v) -> (v == null ? 0 : v) + keyInfo.getDataSize());
      exclusiveReplicatedSizes.compute(previousSnapshotInfo.getSnapshotId(),
          (k, v) -> (v == null ? 0 : v) + keyInfo.getReplicatedSize());
    }
  }

  private Optional<OmKeyInfo> getPreviousSnapshotKeyInfo(long volumeId, OmBucketInfo bucketInfo,
                                                         OmKeyInfo keyInfo, KeyManager keyManager,
                                                         KeyManager previousKeyManager) throws IOException {
    if (keyInfo == null || previousKeyManager == null) {
      return Optional.empty();
    }
    OmKeyInfo prevKeyInfo = keyManager.getPreviousSnapshotOzoneKeyInfo(volumeId, bucketInfo, keyInfo)
        .apply(previousKeyManager);

    // Check if objectIds are matching then the keys are the same.
    if (prevKeyInfo == null || prevKeyInfo.getObjectID() != keyInfo.getObjectID()) {
      return Optional.empty();
    }
    return isBlockLocationInfoSame(prevKeyInfo, keyInfo) ? Optional.of(prevKeyInfo) : Optional.empty();
  }

  private static final class ActiveSnapshotSequences {
    private final long[] createSeqNums;
    private final UUID[] snapshotIds;

    private ActiveSnapshotSequences(long[] createSeqNums, UUID[] snapshotIds) {
      this.createSeqNums = createSeqNums;
      this.snapshotIds = snapshotIds;
    }

    private long[] getCreateSeqNums() {
      return createSeqNums;
    }

    private UUID[] getSnapshotIds() {
      return snapshotIds;
    }
  }

  private static final class SnapshotSeqInfo {
    private final long createSeqNum;
    private final UUID snapshotId;

    private SnapshotSeqInfo(long createSeqNum, UUID snapshotId) {
      this.createSeqNum = createSeqNum;
      this.snapshotId = snapshotId;
    }

    private long getCreateSeqNum() {
      return createSeqNum;
    }

    private UUID getSnapshotId() {
      return snapshotId;
    }
  }
}
