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

package org.apache.hadoop.ozone.local;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Tests for {@link OzoneLocal}.
 */
class TestOzoneLocal {

  @Test
  void runCommandMetadataIsPresentAndPublic() {
    Command command = OzoneLocal.RunCommand.class.getAnnotation(Command.class);

    assertNotNull(command);
    assertEquals("run", command.name());
    assertFalse(command.hidden());
  }

  @Test
  void resolveConfigUsesDefaults() {
    OzoneLocal.RunCommand command = new OzoneLocal.RunCommand(
        Collections.emptyMap());

    LocalOzoneClusterConfig config =
        command.resolveConfig(new OzoneConfiguration());

    assertEquals(Paths.get(System.getProperty("user.home"), ".ozone", "local")
            .toAbsolutePath().normalize(),
        config.getDataDir());
    assertEquals(LocalOzoneClusterConfig.FormatMode.IF_NEEDED,
        config.getFormatMode());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_DATANODES,
        config.getDatanodes());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_STARTUP_TIMEOUT,
        config.getStartupTimeout());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_S3_ACCESS_KEY,
        config.getS3AccessKey());
    assertEquals(LocalOzoneClusterConfig.DEFAULT_S3_SECRET_KEY,
        config.getS3SecretKey());
    assertTrue(config.isS3gEnabled());
    assertFalse(config.isReconEnabled());
  }

  @Test
  void resolveConfigUsesEnvironmentOverrides() {
    Map<String, String> env = new HashMap<>();
    env.put(OzoneLocal.ENV_DATA_DIR, "target/ozone-local-test");
    env.put(OzoneLocal.ENV_FORMAT, "always");
    env.put(OzoneLocal.ENV_DATANODES, "2");
    env.put(OzoneLocal.ENV_HOST, "localhost");
    env.put(OzoneLocal.ENV_BIND_HOST, "0.0.0.0");
    env.put(OzoneLocal.ENV_SCM_PORT, "9860");
    env.put(OzoneLocal.ENV_OM_PORT, "9862");
    env.put(OzoneLocal.ENV_S3G_ENABLED, "false");
    env.put(OzoneLocal.ENV_RECON_ENABLED, "true");
    env.put(OzoneLocal.ENV_RECON_PORT, "9888");
    env.put(OzoneLocal.ENV_EPHEMERAL, "true");
    env.put(OzoneLocal.ENV_STARTUP_TIMEOUT, "120s");
    env.put(OzoneLocal.ENV_S3_ACCESS_KEY, "dev");
    env.put(OzoneLocal.ENV_S3_SECRET_KEY, "devsecret");

    OzoneLocal.RunCommand command = new OzoneLocal.RunCommand(env);

    LocalOzoneClusterConfig config =
        command.resolveConfig(new OzoneConfiguration());

    assertEquals(Paths.get("target/ozone-local-test").toAbsolutePath().normalize(),
        config.getDataDir());
    assertEquals(LocalOzoneClusterConfig.FormatMode.ALWAYS,
        config.getFormatMode());
    assertEquals(2, config.getDatanodes());
    assertEquals("localhost", config.getHost());
    assertEquals("0.0.0.0", config.getBindHost());
    assertEquals(9860, config.getScmPort());
    assertEquals(9862, config.getOmPort());
    assertTrue(config.isReconEnabled());
    assertEquals(9888, config.getReconPort());
    assertEquals(Duration.ofSeconds(120), config.getStartupTimeout());
    assertEquals("dev", config.getS3AccessKey());
    assertEquals("devsecret", config.getS3SecretKey());
    assertTrue(config.isEphemeral());
  }

  @Test
  void resolveConfigUsesAwsCredentialEnvironmentFallback() {
    Map<String, String> env = new HashMap<>();
    env.put(OzoneLocal.ENV_AWS_ACCESS_KEY_ID, "aws-dev");
    env.put(OzoneLocal.ENV_AWS_SECRET_ACCESS_KEY, "aws-devsecret");

    OzoneLocal.RunCommand command = new OzoneLocal.RunCommand(env);

    LocalOzoneClusterConfig config =
        command.resolveConfig(new OzoneConfiguration());

    assertEquals("aws-dev", config.getS3AccessKey());
    assertEquals("aws-devsecret", config.getS3SecretKey());
  }

  @Test
  void resolveConfigRejectsInvalidBooleanEnvironmentValue() {
    Map<String, String> env = new HashMap<>();
    env.put(OzoneLocal.ENV_S3G_ENABLED, "sometimes");
    OzoneLocal.RunCommand command = new OzoneLocal.RunCommand(env);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> command.resolveConfig(new OzoneConfiguration()));

    assertTrue(error.getMessage().contains(OzoneLocal.ENV_S3G_ENABLED));
  }

  @Test
  void resolveConfigRejectsInvalidDurationEnvironmentValue() {
    Map<String, String> env = new HashMap<>();
    env.put(OzoneLocal.ENV_STARTUP_TIMEOUT, "forever");
    OzoneLocal.RunCommand command = new OzoneLocal.RunCommand(env);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> command.resolveConfig(new OzoneConfiguration()));

    assertTrue(error.getMessage().contains(OzoneLocal.ENV_STARTUP_TIMEOUT));
  }

  @Test
  void resolveConfigRejectsDatanodeCountBelowOne() {
    Map<String, String> env = new HashMap<>();
    env.put(OzoneLocal.ENV_DATANODES, "0");
    OzoneLocal.RunCommand command = new OzoneLocal.RunCommand(env);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> command.resolveConfig(new OzoneConfiguration()));

    assertTrue(error.getMessage().contains("at least 1"));
  }

  @Test
  void commandExecutionPrintsStartupSummary() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    StubRuntime runtime = new StubRuntime("localhost", 9860, 9862,
        "http://localhost:9878");
    TestableRunCommand command = new TestableRunCommand(
        Collections.emptyMap(), runtime);
    CommandLine cli = new CommandLine(command);
    cli.setOut(outputWriter(output));

    int exitCode = cli.execute();

    assertEquals(0, exitCode);
    assertTrue(runtime.started);
    assertTrue(runtime.closed);
    String text = output.toString(UTF_8.name());
    assertTrue(text.contains("Local Ozone is running from"));
    assertTrue(text.contains("SCM RPC: localhost:9860"));
    assertTrue(text.contains("OM RPC: localhost:9862"));
    assertTrue(text.contains("S3 endpoint: http://localhost:9878"));
    assertTrue(text.contains("AWS_ACCESS_KEY_ID="
        + LocalOzoneClusterConfig.DEFAULT_S3_ACCESS_KEY));
    assertTrue(text.contains("Press Ctrl+C to stop."));
  }

  @Test
  void commandExecutionPrintsReconSummaryWhenEnabled() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    StubRuntime runtime = new StubRuntime("localhost", 9860, 9862,
        "http://localhost:9878", "http://localhost:9888");
    TestableRunCommand command = new TestableRunCommand(
        Collections.emptyMap(), runtime);
    CommandLine cli = new CommandLine(command);
    cli.setOut(outputWriter(output));

    int exitCode = cli.execute("--with-recon");

    assertEquals(0, exitCode);
    String text = output.toString(UTF_8.name());
    assertTrue(text.contains("Recon endpoint: http://localhost:9888"));
  }

  @Test
  void commandExecutionOmitsS3SummaryWhenDisabled() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    StubRuntime runtime = new StubRuntime("localhost", 9860, 9862, "", "");
    TestableRunCommand command = new TestableRunCommand(
        Collections.emptyMap(), runtime);
    CommandLine cli = new CommandLine(command);
    cli.setOut(outputWriter(output));

    int exitCode = cli.execute("--without-s3g");

    assertEquals(0, exitCode);
    String text = output.toString(UTF_8.name());
    assertFalse(text.contains("S3 endpoint:"));
    assertFalse(text.contains("AWS_ACCESS_KEY_ID="));
    assertTrue(text.contains("Press Ctrl+C to stop."));
  }

  private static java.io.PrintWriter outputWriter(ByteArrayOutputStream output) {
    return new java.io.PrintWriter(new OutputStreamWriter(output, UTF_8), true);
  }

  private static final class TestableRunCommand extends OzoneLocal.RunCommand {

    private final LocalOzoneRuntime runtime;

    private TestableRunCommand(Map<String, String> environment,
        LocalOzoneRuntime runtime) {
      super(environment);
      this.runtime = runtime;
    }

    @Override
    LocalOzoneRuntime createRuntime(LocalOzoneClusterConfig config,
        OzoneConfiguration baseConfiguration) {
      return runtime;
    }

    @Override
    void awaitShutdown(LocalOzoneRuntime localRuntime) {
    }
  }

  private static final class StubRuntime implements LocalOzoneRuntime {

    private final String displayHost;
    private final int scmPort;
    private final int omPort;
    private final String s3Endpoint;
    private final String reconEndpoint;
    private boolean started;
    private boolean closed;

    private StubRuntime(String displayHost, int scmPort, int omPort,
        String s3Endpoint) {
      this(displayHost, scmPort, omPort, s3Endpoint, "");
    }

    private StubRuntime(String displayHost, int scmPort, int omPort,
        String s3Endpoint, String reconEndpoint) {
      this.displayHost = displayHost;
      this.scmPort = scmPort;
      this.omPort = omPort;
      this.s3Endpoint = s3Endpoint;
      this.reconEndpoint = reconEndpoint;
    }

    @Override
    public void start() {
      started = true;
    }

    @Override
    public String getDisplayHost() {
      return displayHost;
    }

    @Override
    public int getScmPort() {
      return scmPort;
    }

    @Override
    public int getOmPort() {
      return omPort;
    }

    @Override
    public int getS3gPort() {
      return 0;
    }

    @Override
    public String getS3Endpoint() {
      return s3Endpoint;
    }

    @Override
    public int getReconPort() {
      return 0;
    }

    @Override
    public String getReconEndpoint() {
      return reconEndpoint;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
