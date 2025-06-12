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

package org.apache.hadoop.ozone.freon;

import static java.util.Collections.emptyMap;

import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.Timer;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.conf.StorageSize;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.protocol.OzoneManagerProtocol;
import org.kohsuke.MetaInfServices;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Key‑level benchmark that supports CREATE, DELETE and LIST with user‑defined
 * weights. It now keeps a concurrent set of *live* keys so DELETE/LIST always
 * operate on existing entries; when the set is empty those two ops are skipped.
 */
@Command(name = "omkeybench",
    description = "Generate CREATE/DELETE/LIST load toward OM with weighted mix.",
    versionProvider = HddsVersionProvider.class,
    mixinStandardHelpOptions = true,
    showDefaultValues = true)
@MetaInfServices(FreonSubcommand.class)
public class OmKeyBenchGenerator extends BaseFreonGenerator implements Runnable {

  // ============ CLI ============

  @Option(names = { "-v", "--volume" }, defaultValue = "vol1", description = "Volume name (created if absent)")
  private String volume;

  @Option(names = { "-b", "--bucket" }, defaultValue = "bucket1", description = "Bucket name (created if absent)")
  private String bucketName;

  @Option(names = "--weights", description = "Comma‑separated weights, e.g. create:5,delete:3,list:2")
  private String weightSpec = "create:1,delete:1,list:1";

  @Option(names = { "--batch-size" }, defaultValue = "1000", description = "Batch size for LIST_KEYS request")
  private int batchSize;

  @Option(names = { "-s", "--size" }, defaultValue = "0", description = "Payload size when creating a key (bytes) "
      + StorageSizeConverter.STORAGE_SIZE_DESCRIPTION, converter = StorageSizeConverter.class)
  private StorageSize dataSize;

  @Option(names = { "--max-live-keys" }, defaultValue = "100000", 
      description = "Maximum number of live keys to maintain for DELETE/LIST operations")
  private int maxLiveKeys;

  @Mixin
  private FreonReplicationOptions replicationOpt;

  // ------------ runtime state ------------
  private final EnumMap<Op, Integer> weights = new EnumMap<>(Op.class);
  private double pCreate; // cumulative boundaries in [0,1)
  private double pDelete;

  private OzoneBucket bucket;
  private ReplicationConfig replConf;
  private OzoneManagerProtocol om;

  private ContentGenerator contentGen;
  private final LongAdder createdCounter = new LongAdder();
  private final LongAdder deletedCounter = new LongAdder();

  /** Set of currently *live* keys for quick random sampling. */
  private final ConcurrentSkipListSet<String> liveKeys = new ConcurrentSkipListSet<>();

  @Override
  public void run() {
    try {
      parseWeights();
      init();
      contentGen = new ContentGenerator(dataSize.toBytes(), 4096);

      OzoneConfiguration conf = createOzoneConfiguration();
      replConf = replicationOpt.fromParamsOrConfig(conf);

      try (OzoneClient client = createOzoneClient(null, conf)) {
        ensureVolumeAndBucketExist(client, volume, bucketName);
        bucket = client.getObjectStore().getVolume(volume).getBucket(bucketName);
        om = createOmClient(conf, null);
        runTests(this::oneIteration);
      } finally {
        if (om != null) {
          om.close();
        }
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  /* ---------------- weight parsing ---------------- */

  private void parseWeights() {
    Arrays.stream(weightSpec.split(","))
        .forEach(pair -> {
          String[] kv = pair.trim().split(":");
          if (kv.length != 2) {
            throw new IllegalArgumentException("Bad --weights element: " + pair);
          }
          Op op = Op.valueOf(kv[0].toUpperCase());
          int w = Integer.parseInt(kv[1]);
          if (w < 0) {
            throw new IllegalArgumentException("Negative weight: " + pair);
          }
          weights.put(op, w);
        });
    if (weights.isEmpty()) {
      throw new IllegalArgumentException("No weights specified");
    }
    int total = weights.values().stream().mapToInt(Integer::intValue).sum();
    pCreate = weights.getOrDefault(Op.CREATE, 0) / (double) total;
    pDelete = pCreate + weights.getOrDefault(Op.DELETE, 0) / (double) total;
  }

  /* ---------------- main loop ---------------- */

  private void oneIteration(long globalCounter) throws Exception {
    double r = ThreadLocalRandom.current().nextDouble();
    Op op = (r < pCreate) ? Op.CREATE : (r < pDelete ? Op.DELETE : Op.LIST);

    switch (op) {
    case CREATE:
      createKey(globalCounter);
      break;
    case DELETE:
      deleteRandomKey();
      break;
    case LIST:
      listRandom();
      break;
    default:
      throw new IllegalStateException();
    }
  }

  /* ---------------- operations ---------------- */

  /**
   * Creates a key and optionally adds it to the liveKeys set for future DELETE/LIST operations.
   * When the liveKeys set reaches maxLiveKeys limit, new keys are still created but not added
   * to the set, preventing performance degradation from large set operations.
   */
  private void createKey(long counter) throws Exception {
    String key = formatKey(counter);
    Timer timer = getMetrics().timer(Op.CREATE.name());
    timer.time(() -> {
      try (OutputStream os = bucket.createKey(key, dataSize.toBytes(), replConf, emptyMap())) {
        contentGen.write(os);
      }
      createdCounter.increment();
      
      // Only add to liveKeys if we haven't reached the limit
      if (liveKeys.size() < maxLiveKeys) {
        liveKeys.add(key);
      }
      return null;
    });
  }

  private void deleteRandomKey() throws Exception {
    if (liveKeys.isEmpty()) {
      return; // nothing to delete now
    }
    String key = pickRandomKey();
    if (key == null) {
      return; // race condition; skip
    }
    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volume)
        .setBucketName(bucketName)
        .setKeyName(key)
        .build();
    Timer timer = getMetrics().timer(Op.DELETE.name());
    timer.time(() -> {
      try {
        om.deleteKey(keyArgs);
        if (liveKeys.remove(key)) {
          deletedCounter.increment();
        }
      } catch (OMException ex) {
        if (ex.getResult() != OMException.ResultCodes.KEY_NOT_FOUND) {
          throw ex;
        }
      }
      return null;
    });
  }

  private void listRandom() throws Exception {
    if (liveKeys.isEmpty()) {
      return; // nothing to list
    }
    String start = pickRandomKey();
    if (start == null) {
      return;
    }
    Timer timer = getMetrics().timer(Op.LIST.name());
    timer.time(() -> {
      om.listKeys(volume, bucketName, start, "", batchSize);
      return null; // ignore size check when data sparse
    });
  }
  /* ---------------- helpers ---------------- */

  private String pickRandomKey() {
    int size = liveKeys.size();
    if (size == 0) {
      return null;
    }
    
    // Convert to array for O(1) random access - more efficient than stream().skip()
    String[] keysArray = liveKeys.toArray(new String[0]);
    if (keysArray.length == 0) {
      return null; // Race condition check
    }
    
    int index = ThreadLocalRandom.current().nextInt(keysArray.length);
    return keysArray[index];
  }

  private static String formatKey(long n) {
    return StringUtils.leftPad(Long.toString(n), 19, '0');
  }

  @Override
  public Supplier<String> realTimeStatusSupplier() {
    final Map<String, Long> maxValueRecorder = new HashMap<>();
    final Map<String, Long> valueRecorder = new HashMap<>();
    final Map<String, Instant> instantsRecorder = new HashMap<>();
    return () -> {
      StringBuilder sb = new StringBuilder();
      int currentLiveKeys = liveKeys.size();
      sb.append(String.format("live=%d/%d created=%d deleted=%d", currentLiveKeys, maxLiveKeys,
          createdCounter.sum(), deletedCounter.sum()));
      
      if (currentLiveKeys >= maxLiveKeys) {
        sb.append(" [LIMIT_REACHED]");
      }
      
      // Add rate information for each operation type
      for (Map.Entry<String, Timer> entry
          : getMetrics().getTimers(MetricFilter.ALL).entrySet()) {
        String name = entry.getKey();
        long maxValue = maxValueRecorder.getOrDefault(name, -1L);
        long preValue = valueRecorder.getOrDefault(name, 0L);
        Instant preInstant = instantsRecorder.getOrDefault(name, Instant.now());

        long curValue = entry.getValue().getCount();
        Instant now = Instant.now();
        long duration = Duration.between(preInstant, now).getSeconds();
        long rate = ((curValue - preValue) / (duration == 0 ? 1 : duration));
        maxValue = Math.max(rate, maxValue);

        maxValueRecorder.put(name, maxValue);
        valueRecorder.put(name, curValue);
        instantsRecorder.put(name, now);
        sb.append(' ')
            .append(name)
            .append(": rate ")
            .append(rate)
            .append(" max ")
            .append(maxValue);
      }
      return sb.toString();
    };
  }

  @Override
  public boolean allowEmptyPrefix() {
    return true;
  }

  enum Op {
    CREATE, DELETE, LIST
  }
}
