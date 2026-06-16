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
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;

/**
 * Test-only deterministic fault wrapper around Ozone client key IO.
 */
public final class DeterministicOzoneClientIo {
  private static final int MAX_ATTEMPTS = 3;

  private final OzoneBucket bucket;
  private final FaultPolicy policy;
  private final Path traceFile;
  private final boolean recordTrace;

  private DeterministicOzoneClientIo(OzoneBucket bucket, FaultPolicy policy,
      Path traceFile, boolean recordTrace) {
    this.bucket = Objects.requireNonNull(bucket, "bucket == null");
    this.policy = Objects.requireNonNull(policy, "policy == null");
    this.traceFile = Objects.requireNonNull(traceFile, "traceFile == null");
    this.recordTrace = recordTrace;
  }

  public static DeterministicOzoneClientIo recording(OzoneBucket bucket,
      FaultPolicy policy, Path traceFile) {
    return new DeterministicOzoneClientIo(bucket, policy, traceFile, true);
  }

  public static RunResult replay(OzoneBucket bucket, Path traceFile)
      throws IOException {
    Trace trace = Trace.read(traceFile);
    if (trace.operations.size() != 1) {
      throw new IOException("Expected exactly one operation in trace: "
          + trace.operations.size());
    }
    Operation operation = trace.operations.get(0);
    return new DeterministicOzoneClientIo(bucket,
        new ScriptedFaultPolicy(trace.faults), traceFile, false)
        .writeKeyWithRetry(operation.key, operation.data,
            operation.replicationConfig());
  }

  public RunResult writeKeyWithRetry(String key, byte[] data,
      ReplicationConfig replicationConfig) throws IOException {
    Operation operation = Operation.write(0, key, data, replicationConfig);
    TraceRecorder trace = recordTrace ? new TraceRecorder(traceFile) : null;
    if (trace != null) {
      trace.recordOperation(operation);
    }

    List<FaultEvent> faults = new ArrayList<>();
    int attempts = 0;
    boolean ambiguousCommitRetried = false;
    FailureClass failureClass = null;
    String failureMessage = null;

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      attempts = attempt;
      try {
        writeOnce(operation, attempt, faults, trace);
        if (ambiguousCommitRetried) {
          failureClass = FailureClass.AMBIGUOUS_COMMIT;
          failureMessage = "Retried ambiguous close for operation "
              + operation.index;
        }
        break;
      } catch (InjectedRecoverableException ex) {
        if (ex.failureClass == FailureClass.AMBIGUOUS_COMMIT) {
          ambiguousCommitRetried = true;
        }
        if (attempt == MAX_ATTEMPTS) {
          failureClass = ex.failureClass;
          failureMessage = ex.getMessage();
        }
      } catch (InjectedNonRecoverableException ex) {
        failureClass = FailureClass.NON_RECOVERABLE;
        failureMessage = ex.getMessage();
        break;
      }
    }

    if (trace != null) {
      trace.write();
    }
    return new RunResult(failureClass == null, failureClass, failureMessage,
        attempts, faults, traceFile);
  }

  private void writeOnce(Operation operation, int attempt,
      List<FaultEvent> faults, TraceRecorder trace)
      throws IOException, InjectedRecoverableException,
      InjectedNonRecoverableException {
    inject(operation, attempt, IoPoint.CREATE_KEY, FaultPhase.BEFORE, faults,
        trace);
    OzoneOutputStream out = bucket.createKey(operation.key,
        operation.data.length, operation.replicationConfig(),
        Collections.emptyMap());
    boolean closed = false;
    try {
      inject(operation, attempt, IoPoint.CREATE_KEY, FaultPhase.AFTER_SUCCESS,
          faults, trace);
      inject(operation, attempt, IoPoint.WRITE, FaultPhase.BEFORE, faults,
          trace);
      out.write(operation.data);
      inject(operation, attempt, IoPoint.WRITE, FaultPhase.AFTER_SUCCESS,
          faults, trace);
      inject(operation, attempt, IoPoint.CLOSE_KEY, FaultPhase.BEFORE, faults,
          trace);
      out.close();
      closed = true;
      inject(operation, attempt, IoPoint.CLOSE_KEY, FaultPhase.AFTER_SUCCESS,
          faults, trace);
    } finally {
      if (!closed) {
        out.close();
      }
    }
  }

  private void inject(Operation operation, int attempt, IoPoint point,
      FaultPhase phase, List<FaultEvent> faults, TraceRecorder trace)
      throws InjectedRecoverableException, InjectedNonRecoverableException {
    Optional<FaultEvent> fault =
        policy.maybeFault(operation, attempt, point, phase);
    if (!fault.isPresent()) {
      return;
    }
    FaultEvent event = fault.get();
    faults.add(event);
    if (trace != null) {
      trace.recordFault(event);
    }
    if (event.action == FaultAction.THROW_NON_RECOVERABLE) {
      throw new InjectedNonRecoverableException(event.toString());
    }
    throw new InjectedRecoverableException(event.failureClass,
        event.toString());
  }

  private static String quote(String value) {
    return "\"" + escape(value) + "\"";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** Recovery class assigned to an injected client IO fault. */
  public enum FailureClass {
    RECOVERABLE,
    AMBIGUOUS_COMMIT,
    NON_RECOVERABLE
  }

  /** Concrete behavior injected at the selected client IO point. */
  public enum FaultAction {
    THROW_RECOVERABLE,
    THROW_NON_RECOVERABLE,
    DROP_RESPONSE_AFTER_SUCCESS
  }

  /** Position of the injected fault relative to the wrapped client call. */
  public enum FaultPhase {
    BEFORE,
    AFTER_SUCCESS
  }

  /** Ozone client IO boundary where a test fault can be injected. */
  public enum IoPoint {
    CREATE_KEY,
    WRITE,
    CLOSE_KEY,
    READ_KEY
  }

  /** Policy that decides whether to inject a fault at a client IO boundary. */
  public interface FaultPolicy {
    Optional<FaultEvent> maybeFault(Operation operation, int attempt,
        IoPoint point, FaultPhase phase);
  }

  /** Fault decision recorded in the JSONL trace and used for replay. */
  public static final class FaultEvent {
    private final int operationIndex;
    private final int attempt;
    private final IoPoint point;
    private final FaultPhase phase;
    private final FaultAction action;
    private final FailureClass failureClass;

    private FaultEvent(int operationIndex, int attempt, IoPoint point,
        FaultPhase phase, FaultAction action, FailureClass failureClass) {
      this.operationIndex = operationIndex;
      this.attempt = attempt;
      this.point = point;
      this.phase = phase;
      this.action = action;
      this.failureClass = failureClass;
    }

    public static FaultEvent of(int operationIndex, int attempt, IoPoint point,
        FaultPhase phase, FaultAction action, FailureClass failureClass) {
      return new FaultEvent(operationIndex, attempt, point, phase, action,
          failureClass);
    }

    private boolean matches(Operation operation, int currentAttempt,
        IoPoint currentPoint, FaultPhase currentPhase) {
      return operationIndex == operation.index
          && attempt == currentAttempt
          && point == currentPoint
          && phase == currentPhase;
    }

    private String toJsonLine() {
      return "{\"type\":\"fault\",\"operationIndex\":" + operationIndex
          + ",\"attempt\":" + attempt
          + ",\"point\":" + quote(point.name())
          + ",\"phase\":" + quote(phase.name())
          + ",\"action\":" + quote(action.name())
          + ",\"failureClass\":" + quote(failureClass.name()) + "}";
    }

    private static FaultEvent fromJson(Map<String, String> json) {
      return of(Integer.parseInt(json.get("operationIndex")),
          Integer.parseInt(json.get("attempt")),
          IoPoint.valueOf(json.get("point")),
          FaultPhase.valueOf(json.get("phase")),
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
          && attempt == other.attempt
          && point == other.point
          && phase == other.phase
          && action == other.action
          && failureClass == other.failureClass;
    }

    @Override
    public int hashCode() {
      return Objects.hash(operationIndex, attempt, point, phase, action,
          failureClass);
    }

    @Override
    public String toString() {
      return "FaultEvent{"
          + "operationIndex=" + operationIndex
          + ", attempt=" + attempt
          + ", point=" + point
          + ", phase=" + phase
          + ", action=" + action
          + ", failureClass=" + failureClass
          + '}';
    }
  }

  /** Result of a deterministic Ozone client IO run. */
  public static final class RunResult {
    private final boolean successful;
    private final FailureClass failureClass;
    private final String failureMessage;
    private final int attempts;
    private final List<FaultEvent> faults;
    private final Path traceFile;

    private RunResult(boolean successful, FailureClass failureClass,
        String failureMessage, int attempts, List<FaultEvent> faults,
        Path traceFile) {
      this.successful = successful;
      this.failureClass = failureClass;
      this.failureMessage = failureMessage;
      this.attempts = attempts;
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

    public int attempts() {
      return attempts;
    }

    public List<FaultEvent> faults() {
      return faults;
    }

    public Path traceFile() {
      return traceFile;
    }
  }

  /** Fault policy that consumes a fixed list of fault events once. */
  public static final class ScriptedFaultPolicy implements FaultPolicy {
    private final List<FaultEvent> faults;
    private final Set<Integer> consumed = new HashSet<>();

    public ScriptedFaultPolicy(List<FaultEvent> faults) {
      this.faults = new ArrayList<>(faults);
    }

    @Override
    public Optional<FaultEvent> maybeFault(Operation operation, int attempt,
        IoPoint point, FaultPhase phase) {
      for (int i = 0; i < faults.size(); i++) {
        FaultEvent fault = faults.get(i);
        if (!consumed.contains(i)
            && fault.matches(operation, attempt, point, phase)) {
          consumed.add(i);
          return Optional.of(fault);
        }
      }
      return Optional.empty();
    }
  }

  private static final class Operation {
    private final int index;
    private final String key;
    private final byte[] data;
    private final ReplicationType replicationType;
    private final ReplicationFactor replicationFactor;

    private Operation(int index, String key, byte[] data,
        ReplicationType replicationType, ReplicationFactor replicationFactor) {
      this.index = index;
      this.key = key;
      this.data = data.clone();
      this.replicationType = replicationType;
      this.replicationFactor = replicationFactor;
    }

    private static Operation write(int index, String key, byte[] data,
        ReplicationConfig replicationConfig) {
      return new Operation(index, key, data,
          ReplicationType.valueOf(replicationConfig.getReplicationType().name()),
          ReplicationFactor.valueOf(replicationConfig.getRequiredNodes()));
    }

    private ReplicationConfig replicationConfig() {
      return ReplicationConfig.fromTypeAndFactor(replicationType,
          replicationFactor);
    }

    private String toJsonLine() {
      return "{\"type\":\"operation\",\"index\":" + index
          + ",\"key\":" + quote(key)
          + ",\"data\":" + quote(Base64.getEncoder().encodeToString(data))
          + ",\"replicationType\":" + quote(replicationType.name())
          + ",\"replicationFactor\":" + quote(replicationFactor.name()) + "}";
    }

    private static Operation fromJson(Map<String, String> json) {
      return new Operation(Integer.parseInt(json.get("index")),
          json.get("key"),
          Base64.getDecoder().decode(json.get("data")),
          ReplicationType.valueOf(json.get("replicationType")),
          ReplicationFactor.valueOf(json.get("replicationFactor")));
    }
  }

  private static final class TraceRecorder {
    private final Path traceFile;
    private final List<String> lines = new ArrayList<>();

    private TraceRecorder(Path traceFile) {
      this.traceFile = traceFile;
      lines.add("{\"type\":\"meta\",\"version\":1}");
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
    private final List<Operation> operations;
    private final List<FaultEvent> faults;

    private Trace(List<Operation> operations, List<FaultEvent> faults) {
      this.operations = operations;
      this.faults = faults;
    }

    private static Trace read(Path traceFile) throws IOException {
      List<Operation> operations = new ArrayList<>();
      List<FaultEvent> faults = new ArrayList<>();
      for (String line : Files.readAllLines(traceFile, StandardCharsets.UTF_8)) {
        Map<String, String> json = parseJsonObject(line);
        if ("operation".equals(json.get("type"))) {
          operations.add(Operation.fromJson(json));
        } else if ("fault".equals(json.get("type"))) {
          faults.add(FaultEvent.fromJson(json));
        }
      }
      return new Trace(operations, faults);
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
        throw new IllegalArgumentException("Expected ':' in JSON object: "
            + line);
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
    while (position < line.length()
        && Character.isWhitespace(line.charAt(position))) {
      position++;
    }
    return position;
  }

  private static JsonString readJsonString(String line, int position) {
    if (line.charAt(position) != '"') {
      throw new IllegalArgumentException("Expected string in JSON object: "
          + line);
    }
    StringBuilder value = new StringBuilder();
    for (int i = position + 1; i < line.length(); i++) {
      char current = line.charAt(i);
      if (current == '\\') {
        i++;
        if (i >= line.length()) {
          throw new IllegalArgumentException(
              "Unterminated escape in JSON object: " + line);
        }
        value.append(line.charAt(i));
      } else if (current == '"') {
        return new JsonString(value.toString(), i + 1);
      } else {
        value.append(current);
      }
    }
    throw new IllegalArgumentException("Unterminated string in JSON object: "
        + line);
  }

  private static final class JsonString {
    private final String value;
    private final int nextPosition;

    private JsonString(String value, int nextPosition) {
      this.value = value;
      this.nextPosition = nextPosition;
    }
  }

  private static final class InjectedRecoverableException extends Exception {
    private final FailureClass failureClass;

    private InjectedRecoverableException(FailureClass failureClass,
        String message) {
      super(message);
      this.failureClass = failureClass;
    }
  }

  private static final class InjectedNonRecoverableException extends Exception {
    private InjectedNonRecoverableException(String message) {
      super(message);
    }
  }
}
