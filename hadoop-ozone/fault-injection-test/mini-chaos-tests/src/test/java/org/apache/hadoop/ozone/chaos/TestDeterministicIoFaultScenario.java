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

package org.apache.hadoop.ozone.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.FailureClass;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.FaultAction;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.FaultEvent;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.FaultPhase;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.FaultPoint;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.Operation;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.OperationType;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.RunResult;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.ScriptedFaultPolicy;
import org.apache.hadoop.ozone.chaos.DeterministicIoFaultScenario.SeededFaultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * POC tests for deterministic IO fault injection and replay.
 */
class TestDeterministicIoFaultScenario {

  @TempDir
  private Path tempDir;

  @Test
  void recoverablePreIoFailureIsRetriedWithoutDuplicatingState()
      throws Exception {
    Operation append = Operation.append(0, "k0", "a");
    FaultEvent recoverableFault = FaultEvent.of(0, OperationType.APPEND,
        FaultPhase.BEFORE, FaultPoint.APPEND, FaultAction.THROW_RECOVERABLE,
        FailureClass.RECOVERABLE);

    RunResult result = DeterministicIoFaultScenario.runOperations(
        Collections.singletonList(append),
        new ScriptedFaultPolicy(Collections.singletonList(recoverableFault)),
        true, traceFile("recoverable.jsonl"));

    assertTrue(result.isSuccessful());
    assertEquals("a", result.actualValue("k0"));
    assertEquals("a", result.modelValue("k0"));
    assertEquals(Collections.singletonList(recoverableFault), result.faults());
  }

  @Test
  void ambiguousCommitDetectsDuplicateSideEffect() throws Exception {
    Operation append = Operation.append(0, "k0", "a");
    FaultEvent ackLoss = FaultEvent.of(0, OperationType.APPEND,
        FaultPhase.AFTER_SUCCESS, FaultPoint.APPEND,
        FaultAction.DROP_RESPONSE_AFTER_SUCCESS,
        FailureClass.AMBIGUOUS_COMMIT);

    RunResult result = DeterministicIoFaultScenario.runOperations(
        Collections.singletonList(append),
        new ScriptedFaultPolicy(Collections.singletonList(ackLoss)),
        false, traceFile("ambiguous.jsonl"));

    assertFalse(result.isSuccessful());
    assertEquals(FailureClass.AMBIGUOUS_COMMIT, result.failureClass());
    assertTrue(result.failureMessage().contains("Duplicate side effect"));
    assertEquals("a", result.modelValue("k0"));
    assertEquals("aa", result.actualValue("k0"));
  }

  @Test
  void replayReproducesAmbiguousCommitFailure() throws Exception {
    Operation append = Operation.append(0, "k0", "a");
    FaultEvent ackLoss = FaultEvent.of(0, OperationType.APPEND,
        FaultPhase.AFTER_SUCCESS, FaultPoint.APPEND,
        FaultAction.DROP_RESPONSE_AFTER_SUCCESS,
        FailureClass.AMBIGUOUS_COMMIT);
    Path trace = traceFile("replay.jsonl");

    RunResult firstRun = DeterministicIoFaultScenario.runOperations(
        Collections.singletonList(append),
        new ScriptedFaultPolicy(Collections.singletonList(ackLoss)),
        false, trace);
    RunResult replay = DeterministicIoFaultScenario.replay(trace, false);

    assertFalse(firstRun.isSuccessful());
    assertFalse(replay.isSuccessful());
    assertEquals(firstRun.failureMessage(), replay.failureMessage());
    assertEquals(firstRun.actualValue("k0"), replay.actualValue("k0"));
  }

  @Test
  void idempotentAppendSurvivesAmbiguousCommitReplay() throws Exception {
    Operation append = Operation.append(0, "k0", "a");
    FaultEvent ackLoss = FaultEvent.of(0, OperationType.APPEND,
        FaultPhase.AFTER_SUCCESS, FaultPoint.APPEND,
        FaultAction.DROP_RESPONSE_AFTER_SUCCESS,
        FailureClass.AMBIGUOUS_COMMIT);
    Path trace = traceFile("idempotent-replay.jsonl");

    DeterministicIoFaultScenario.runOperations(Collections.singletonList(append),
        new ScriptedFaultPolicy(Collections.singletonList(ackLoss)), false, trace);
    RunResult replayWithDedupe =
        DeterministicIoFaultScenario.replay(trace, true);

    assertTrue(replayWithDedupe.isSuccessful());
    assertEquals("a", replayWithDedupe.actualValue("k0"));
  }

  @Test
  void seededExplorationFindsAmbiguousCommitBug() throws Exception {
    RunResult firstFailure = null;
    for (long seed = 0; seed < 8; seed++) {
      Path trace = traceFile("seed-" + seed + ".jsonl");
      RunResult result = DeterministicIoFaultScenario.explore(seed,
          24, new SeededFaultPolicy(seed, 35), false, trace);
      if (!result.isSuccessful()) {
        firstFailure = result;
        break;
      }
    }

    assertNotNull(firstFailure);
    assertEquals(FailureClass.AMBIGUOUS_COMMIT, firstFailure.failureClass());
    assertTrue(Files.exists(firstFailure.traceFile()));
  }

  private Path traceFile(String name) {
    return tempDir.resolve(name);
  }
}
