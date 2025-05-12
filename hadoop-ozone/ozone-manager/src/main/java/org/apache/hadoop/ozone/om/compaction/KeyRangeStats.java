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

import org.rocksdb.TableProperties;

/**
 * Statistics for a key range.
 */
public class KeyRangeStats {
  private int numEntries;
  private int numDeletion;

  public KeyRangeStats(int numEntries, int numDeletion) {
    this.numEntries = numEntries;
    this.numDeletion = numDeletion;
  }

  public static KeyRangeStats fromTableProperties(TableProperties properties) {
    return new KeyRangeStats(
        (int) properties.getNumEntries(),
        (int) properties.getNumDeletions());
  }

  public void add(KeyRangeStats other) {
    this.numEntries += other.numEntries;
    this.numDeletion += other.numDeletion;
  }

  public int getNumEntries() {
    return numEntries;
  }

  public int getNumDeletion() {
    return numDeletion;
  }

  public double getTombstonePercentage() {
    if (numEntries == 0) {
      return 0.0;
    }
    return ((double) numDeletion / numEntries) * 100;
  }

  @Override
  public String toString() {
    return "KeyRangeStats{" +
        "numEntries=" + numEntries +
        ", numDeletion=" + numDeletion +
        ", tombstonePercentage=" + getTombstonePercentage() +
        '}';
  }
}
