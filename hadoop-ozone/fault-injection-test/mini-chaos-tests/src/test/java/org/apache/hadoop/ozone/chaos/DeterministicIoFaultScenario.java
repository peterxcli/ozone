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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Test-only POC for deterministic IO fault injection and replay.
 */
public final class DeterministicIoFaultScenario {
  private static final int MAX_ATTEMPTS = 3;
  private static final String DUPLICATE_SIDE_EFFECT = "Duplicate side effect";

  private DeterministicIoFaultScenario() {
  }

  /**
   * Recovery class assigned to an injected fault.
   */
  public enum FailureClass {
    RECOVERABLE,
    AMBIGUOUS_COMMIT,
    NON_RECOVERABLE
  }

  /**
   * Concrete behavior injected at a fault point.
   */
  public enum FaultAction {
    THROW_RECOVERABLE,
    THROW_NON_RECOVERABLE,
    DROP_RESPONSE_AFTER_SUCCESS,
    CORRUPT_READ
  }

  /**
   * Position of the injected fault relative to the wrapped IO call.
   */
  public enum FaultPhase {
    BEFORE,
    AFTER_SUCCESS
  }

  /**
   * Logical IO boundary where a fault can be injected.
   */
  public enum FaultPoint {
    APPEND,
    READ
  }

  /**
   * Operation type recorded in the deterministic trace.
   */
  public enum OperationType {
    APPEND,
    READ
  }

  /**
   * Policy that decides whether to inject a fault at the current boundary.
   */
  public interface FaultPolicy {
    Optional<FaultEvent> maybeFault(Operation operation, FaultPhase phase, FaultPoint point);
  }

  public static RunResult runOperations(List<Operation> operations, FaultPolicy policy,
      boolean idempotentAppends, Path traceFile) throws IOException {
    return runOperations(operations, policy, idempotentAppends, traceFile, -1);
  }

  public static RunResult replay(Path traceFile, boolean idempotentAppends) throws IOException {
    Trace trace = Trace.read(traceFile);
    return runOperations(trace.operations, new ScriptedFaultPolicy(trace.faults),
        idempotentAppends, traceFile, trace.seed);
  }

  public static RunResult explore(long seed, int operationCount, FaultPolicy policy,
      boolean idempotentAppends, Path traceFile) throws IOException {
    List<Operation> operations = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      operations.add(Operation.append(i, "k" + (i % 3), "v" + i));
    }
    return runOperations(operations, policy, idempotentAppends, traceFile, seed);
  }

  private static RunResult runOperations(List<Operation> operations, FaultPolicy policy,
      boolean idempotentAppends, Path traceFile, long seed) throws IOException {
    TraceRecorder trace = new TraceRecorder(traceFile, seed, operations.size());
    List<FaultEvent> faults = new ArrayList<>();
    InMemoryAppendStore store = new InMemoryAppendStore(idempotentAppends);
    FaultInjectedKeyValueIo io = new FaultInjectedKeyValueIo(store, policy, faults, trace);
    Map<String, String> model = new LinkedHashMap<>();
    FailureClass failureClass = null;
    String failureMessage = null;

    for (Operation operation : operations) {
      trace.recordOperation(operation);
      OperationOutcome outcome = executeWithRetry(io, operation);
      if (!outcome.successful) {
        failureClass = outcome.failureClass;
        failureMessage = outcome.failureMessage;
        break;
      }
      applyToModel(model, operation);
    }

    Map<String, String> actual = store.snapshot();
    if (failureClass == null && !actual.equals(model)) {
      failureClass = containsAmbiguousCommit(faults)
          ? FailureClass.AMBIGUOUS_COMMIT : FailureClass.NON_RECOVERABLE;
      failureMessage = DUPLICATE_SIDE_EFFECT + ": model=" + model + ", actual=" + actual;
    }

    trace.write();
    return new RunResult(failureClass == null, failureClass, failureMessage,
        model, actual, faults, traceFile);
  }

  private static OperationOutcome executeWithRetry(FaultInjectedKeyValueIo io,
      Operation operation) {
    RecoverableIoException lastRecoverable = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        if (operation.operationType == OperationType.APPEND) {
          io.append(operation);
        } else {
          io.read(operation);
        }
        return OperationOutcome.success();
      } catch (RecoverableIoException ex) {
        lastRecoverable = ex;
      } catch (NonRecoverableIoException ex) {
        return OperationOutcome.failure(FailureClass.NON_RECOVERABLE, ex.getMessage());
      }
    }
    return OperationOutcome.failure(FailureClass.RECOVERABLE, lastRecoverable.getMessage());
  }

  private static void applyToModel(Map<String, String> model, Operation operation) {
    if (operation.operationType == OperationType.APPEND) {
      model.put(operation.key, append(model.get(operation.key), operation.value));
    }
  }

  private static boolean containsAmbiguousCommit(List<FaultEvent> faults) {
    for (FaultEvent fault : faults) {
      if (fault.failureClass == FailureClass.AMBIGUOUS_COMMIT) {
        return true;
      }
    }
    return false;
  }

  private static String append(String currentValue, String value) {
    return currentValue == null ? value : currentValue + value;
  }

  private static String quote(String value) {
    return "\"" + escape(value) + "\"";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * Logical workload operation recorded in the trace.
   */
  public static final class Operation {
    private final int index;
    private final OperationType operationType;
    private final String key;
    private final String value;

    private Operation(int index, OperationType operationType, String key, String value) {
      this.index = index;
      this.operationType = operationType;
      this.key = key;
      this.value = value;
    }

    public static Operation append(int index, String key, String value) {
      return new Operation(index, OperationType.APPEND, key, value);
    }

    public static Operation read(int index, String key) {
      return new Operation(index, OperationType.READ, key, "");
    }

    private String toJsonLine() {
      return "{\"type\":\"operation\",\"index\":" + index
          + ",\"operation\":" + quote(operationType.name())
          + ",\"key\":" + quote(key)
          + ",\"value\":" + quote(nullToEmpty(value)) + "}";
    }

    private static Operation fromJson(Map<String, String> json) {
      int index = Integer.parseInt(json.get("index"));
      OperationType operation = OperationType.valueOf(json.get("operation"));
      if (operation == OperationType.APPEND) {
        return append(index, json.get("key"), json.get("value"));
      }
      return read(index, json.get("key"));
    }
  }

  /**
   * Fault decision recorded in the trace and used for replay.
   */
  public static final class FaultEvent {
    private final int operationIndex;
    private final OperationType operationType;
    private final FaultPhase phase;
    private final FaultPoint point;
    private final FaultAction action;
    private final FailureClass failureClass;

    private FaultEvent(int operationIndex, OperationType operationType, FaultPhase phase,
        FaultPoint point, FaultAction action, FailureClass failureClass) {
      this.operationIndex = operationIndex;
      this.operationType = operationType;
      this.phase = phase;
      this.point = point;
      this.action = action;
      this.failureClass = failureClass;
    }

    public static FaultEvent of(int operationIndex, OperationType operationType,
        FaultPhase phase, FaultPoint point, FaultAction action, FailureClass failureClass) {
      return new FaultEvent(operationIndex, operationType, phase, point, action, failureClass);
    }

    private boolean matches(Operation operation, FaultPhase faultPhase, FaultPoint faultPoint) {
      return operationIndex == operation.index
          && operationType == operation.operationType
          && phase == faultPhase
          && point == faultPoint;
    }

    private String toJsonLine() {
      return "{\"type\":\"fault\",\"operationIndex\":" + operationIndex
          + ",\"operation\":" + quote(operationType.name())
          + ",\"phase\":" + quote(phase.name())
          + ",\"point\":" + quote(point.name())
          + ",\"action\":" + quote(action.name())
          + ",\"failureClass\":" + quote(failureClass.name()) + "}";
    }

    private static FaultEvent fromJson(Map<String, String> json) {
      return of(Integer.parseInt(json.get("operationIndex")),
          OperationType.valueOf(json.get("operation")),
          FaultPhase.valueOf(json.get("phase")),
          FaultPoint.valueOf(json.get("point")),
          FaultAction.valueOf(json.get("action")),
          FailureClass.valueOf(json.get("failureClass")));
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof FaultEvent)) {
        return false;
      }
      FaultEvent other = (FaultEvent) obj;
      return operationIndex == other.operationIndex
          && operationType == other.operationType
          && phase == other.phase
          && point == other.point
          && action == other.action
          && failureClass == other.failureClass;
    }

    @Override
    public int hashCode() {
      return Objects.hash(operationIndex, operationType, phase, point, action, failureClass);
    }

    @Override
    public String toString() {
      return "FaultEvent{"
          + "operationIndex=" + operationIndex
          + ", operationType=" + operationType
          + ", phase=" + phase
          + ", point=" + point
          + ", action=" + action
          + ", failureClass=" + failureClass
          + '}';
    }
  }

  /**
   * Result of one scenario run, including oracle and actual state snapshots.
   */
  public static final class RunResult {
    private final boolean successful;
    private final FailureClass failureClass;
    private final String failureMessage;
    private final Map<String, String> model;
    private final Map<String, String> actual;
    private final List<FaultEvent> faults;
    private final Path traceFile;

    private RunResult(boolean successful, FailureClass failureClass, String failureMessage,
        Map<String, String> model, Map<String, String> actual, List<FaultEvent> faults,
        Path traceFile) {
      this.successful = successful;
      this.failureClass = failureClass;
      this.failureMessage = failureMessage;
      this.model = Collections.unmodifiableMap(new LinkedHashMap<>(model));
      this.actual = Collections.unmodifiableMap(new LinkedHashMap<>(actual));
      this.faults = Collections.unmodifiableList(new ArrayList<>(faults));
      this.traceFile = traceFile;
    }

    public boolean isSuccessful() {
      return successful;
    }

    public FailureClass failureClass() {
      return failureClass;
    }

    public String failureMessage() {
      return failureMessage;
    }

    public String modelValue(String key) {
      return model.get(key);
    }

    public String actualValue(String key) {
      return actual.get(key);
    }

    public List<FaultEvent> faults() {
      return faults;
    }

    public Path traceFile() {
      return traceFile;
    }
  }

  /**
   * Fault policy that consumes a fixed list of trace events exactly once.
   */
  public static final class ScriptedFaultPolicy implements FaultPolicy {
    private final List<FaultEvent> faults;
    private final Set<Integer> consumed = new HashSet<>();

    public ScriptedFaultPolicy(List<FaultEvent> faults) {
      this.faults = new ArrayList<>(faults);
    }

    @Override
    public Optional<FaultEvent> maybeFault(Operation operation, FaultPhase phase,
        FaultPoint point) {
      for (int i = 0; i < faults.size(); i++) {
        FaultEvent fault = faults.get(i);
        if (!consumed.contains(i) && fault.matches(operation, phase, point)) {
          consumed.add(i);
          return Optional.of(fault);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * Deterministic exploration policy for ambiguous append commits.
   */
  public static final class SeededFaultPolicy implements FaultPolicy {
    private final long seed;
    private final int ambiguousCommitPercent;
    private final Set<Integer> injectedOperations = new HashSet<>();

    public SeededFaultPolicy(long seed, int ambiguousCommitPercent) {
      this.seed = seed;
      this.ambiguousCommitPercent = ambiguousCommitPercent;
    }

    @Override
    public Optional<FaultEvent> maybeFault(Operation operation, FaultPhase phase,
        FaultPoint point) {
      if (operation.operationType != OperationType.APPEND
          || phase != FaultPhase.AFTER_SUCCESS
          || point != FaultPoint.APPEND
          || injectedOperations.contains(operation.index)) {
        return Optional.empty();
      }

      int draw = Math.floorMod((int) (seed * 31 + operation.index * 17), 100);
      if (draw < ambiguousCommitPercent) {
        injectedOperations.add(operation.index);
        return Optional.of(FaultEvent.of(operation.index, OperationType.APPEND,
            FaultPhase.AFTER_SUCCESS, FaultPoint.APPEND,
            FaultAction.DROP_RESPONSE_AFTER_SUCCESS, FailureClass.AMBIGUOUS_COMMIT));
      }
      return Optional.empty();
    }
  }

  private static final class FaultInjectedKeyValueIo {
    private final InMemoryAppendStore store;
    private final FaultPolicy policy;
    private final List<FaultEvent> faults;
    private final TraceRecorder trace;

    private FaultInjectedKeyValueIo(InMemoryAppendStore store, FaultPolicy policy,
        List<FaultEvent> faults, TraceRecorder trace) {
      this.store = store;
      this.policy = policy;
      this.faults = faults;
      this.trace = trace;
    }

    private void append(Operation operation)
        throws RecoverableIoException, NonRecoverableIoException {
      inject(operation, FaultPhase.BEFORE, FaultPoint.APPEND);
      store.append(operation);
      inject(operation, FaultPhase.AFTER_SUCCESS, FaultPoint.APPEND);
    }

    private String read(Operation operation)
        throws RecoverableIoException, NonRecoverableIoException {
      inject(operation, FaultPhase.BEFORE, FaultPoint.READ);
      String value = store.read(operation.key);
      Optional<FaultEvent> fault =
          policy.maybeFault(operation, FaultPhase.AFTER_SUCCESS, FaultPoint.READ);
      if (fault.isPresent()) {
        recordFault(fault.get());
        if (fault.get().action == FaultAction.CORRUPT_READ) {
          return nullToEmpty(value) + "#corrupt";
        }
        throwFault(fault.get());
      }
      return value;
    }

    private void inject(Operation operation, FaultPhase phase, FaultPoint point)
        throws RecoverableIoException, NonRecoverableIoException {
      Optional<FaultEvent> fault = policy.maybeFault(operation, phase, point);
      if (fault.isPresent()) {
        recordFault(fault.get());
        throwFault(fault.get());
      }
    }

    private void recordFault(FaultEvent fault) {
      faults.add(fault);
      trace.recordFault(fault);
    }

    private void throwFault(FaultEvent fault)
        throws RecoverableIoException, NonRecoverableIoException {
      if (fault.action == FaultAction.THROW_NON_RECOVERABLE) {
        throw new NonRecoverableIoException(fault.toString());
      }
      throw new RecoverableIoException(fault.toString());
    }
  }

  private static final class InMemoryAppendStore {
    private final boolean idempotentAppends;
    private final Set<Integer> appliedOperations = new HashSet<>();
    private final Map<String, String> values = new LinkedHashMap<>();

    private InMemoryAppendStore(boolean idempotentAppends) {
      this.idempotentAppends = idempotentAppends;
    }

    private void append(Operation operation) {
      if (idempotentAppends && !appliedOperations.add(operation.index)) {
        return;
      }
      values.put(operation.key, DeterministicIoFaultScenario.append(
          values.get(operation.key), operation.value));
    }

    private String read(String key) {
      return values.get(key);
    }

    private Map<String, String> snapshot() {
      return new LinkedHashMap<>(values);
    }
  }

  private static final class OperationOutcome {
    private final boolean successful;
    private final FailureClass failureClass;
    private final String failureMessage;

    private OperationOutcome(boolean successful, FailureClass failureClass,
        String failureMessage) {
      this.successful = successful;
      this.failureClass = failureClass;
      this.failureMessage = failureMessage;
    }

    private static OperationOutcome success() {
      return new OperationOutcome(true, null, null);
    }

    private static OperationOutcome failure(FailureClass failureClass, String failureMessage) {
      return new OperationOutcome(false, failureClass, failureMessage);
    }
  }

  private static final class TraceRecorder {
    private final Path traceFile;
    private final List<String> lines = new ArrayList<>();

    private TraceRecorder(Path traceFile, long seed, int operationCount) {
      this.traceFile = traceFile;
      lines.add("{\"type\":\"meta\",\"seed\":" + seed
          + ",\"operationCount\":" + operationCount + "}");
    }

    private void recordOperation(Operation operation) {
      lines.add(operation.toJsonLine());
    }

    private void recordFault(FaultEvent fault) {
      lines.add(fault.toJsonLine());
    }

    private void write() throws IOException {
      Path parent = traceFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(traceFile, lines, StandardCharsets.UTF_8);
    }
  }

  private static final class Trace {
    private final long seed;
    private final List<Operation> operations;
    private final List<FaultEvent> faults;

    private Trace(long seed, List<Operation> operations, List<FaultEvent> faults) {
      this.seed = seed;
      this.operations = operations;
      this.faults = faults;
    }

    private static Trace read(Path traceFile) throws IOException {
      long seed = -1;
      List<Operation> operations = new ArrayList<>();
      List<FaultEvent> faults = new ArrayList<>();
      for (String line : Files.readAllLines(traceFile, StandardCharsets.UTF_8)) {
        Map<String, String> json = parseJsonObject(line);
        String type = json.get("type");
        if ("meta".equals(type)) {
          seed = Long.parseLong(json.get("seed"));
        } else if ("operation".equals(type)) {
          operations.add(Operation.fromJson(json));
        } else if ("fault".equals(type)) {
          faults.add(FaultEvent.fromJson(json));
        }
      }
      return new Trace(seed, operations, faults);
    }
  }

  private static Map<String, String> parseJsonObject(String line) {
    Map<String, String> values = new HashMap<>();
    int position = skipWhitespace(line, 0);
    if (line.charAt(position) != '{') {
      throw new IllegalArgumentException("Expected JSON object: " + line);
    }
    position++;
    while (position < line.length()) {
      position = skipWhitespace(line, position);
      if (line.charAt(position) == '}') {
        return values;
      }
      JsonString key = readJsonString(line, position);
      position = skipWhitespace(line, key.nextPosition);
      if (line.charAt(position) != ':') {
        throw new IllegalArgumentException("Expected ':' in JSON object: " + line);
      }
      position = skipWhitespace(line, position + 1);
      String value;
      if (line.charAt(position) == '"') {
        JsonString jsonValue = readJsonString(line, position);
        value = jsonValue.value;
        position = jsonValue.nextPosition;
      } else {
        int start = position;
        while (position < line.length()
            && line.charAt(position) != ','
            && line.charAt(position) != '}') {
          position++;
        }
        value = line.substring(start, position).trim();
      }
      values.put(key.value, value);
      position = skipWhitespace(line, position);
      if (line.charAt(position) == ',') {
        position++;
      }
    }
    throw new IllegalArgumentException("Unterminated JSON object: " + line);
  }

  private static int skipWhitespace(String line, int position) {
    while (position < line.length() && Character.isWhitespace(line.charAt(position))) {
      position++;
    }
    return position;
  }

  private static JsonString readJsonString(String line, int position) {
    if (line.charAt(position) != '"') {
      throw new IllegalArgumentException("Expected string in JSON object: " + line);
    }
    StringBuilder value = new StringBuilder();
    for (int i = position + 1; i < line.length(); i++) {
      char current = line.charAt(i);
      if (current == '\\') {
        i++;
        if (i >= line.length()) {
          throw new IllegalArgumentException("Unterminated escape in JSON object: " + line);
        }
        value.append(line.charAt(i));
      } else if (current == '"') {
        return new JsonString(value.toString(), i + 1);
      } else {
        value.append(current);
      }
    }
    throw new IllegalArgumentException("Unterminated string in JSON object: " + line);
  }

  private static final class JsonString {
    private final String value;
    private final int nextPosition;

    private JsonString(String value, int nextPosition) {
      this.value = value;
      this.nextPosition = nextPosition;
    }
  }

  private static final class RecoverableIoException extends Exception {
    private RecoverableIoException(String message) {
      super(message);
    }
  }

  private static final class NonRecoverableIoException extends Exception {
    private NonRecoverableIoException(String message) {
      super(message);
    }
  }
}
