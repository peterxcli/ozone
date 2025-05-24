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

import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.utils.db.KeyRange;

/**
 * CompoundKeyRangeStats is a class that contains a list of KeyRangeStats and a
 * compound KeyRangeStats.
 */
public class CompoundKeyRangeStats {
  private KeyRangeStats compoundStats;
  private List<Pair<KeyRange, KeyRangeStats>> keyRangeStatsList;

  public CompoundKeyRangeStats(List<Pair<KeyRange, KeyRangeStats>> keyRangeStatsList) {
    this.keyRangeStatsList = keyRangeStatsList;
    for (Pair<KeyRange, KeyRangeStats> entry : keyRangeStatsList) {
      if (compoundStats == null) {
        compoundStats = entry.getRight();
      } else {
        compoundStats.add(entry.getRight());
      }
    }
  }

  public void index() {
    // TODO: use some interval tree or segment tree to index the key range stats as
    // the range may overlap with each other.
  }

  public KeyRangeStats getCompoundStats() {
    return compoundStats;
  }

  public List<Pair<KeyRange, KeyRangeStats>> getKeyRangeStatsList() {
    return keyRangeStatsList;
  }
}
