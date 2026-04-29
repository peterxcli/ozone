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

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hdds.cli.GenericCli;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.ratis.util.ExitUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * CLI entry point for local single-node Ozone.
 */
@Command(name = "ozone local",
    description = "Run a single-node local Ozone cluster",
    versionProvider = HddsVersionProvider.class,
    mixinStandardHelpOptions = true,
    subcommands = {
        OzoneLocal.RunCommand.class
    })
public class OzoneLocal extends GenericCli {

  static final String ENV_DATA_DIR = "OZONE_LOCAL_DATA_DIR";
  static final String ENV_FORMAT = "OZONE_LOCAL_FORMAT";
  static final String ENV_DATANODES = "OZONE_LOCAL_DATANODES";
  static final String ENV_HOST = "OZONE_LOCAL_HOST";
  static final String ENV_BIND_HOST = "OZONE_LOCAL_BIND_HOST";
  static final String ENV_SCM_PORT = "OZONE_LOCAL_SCM_PORT";
  static final String ENV_OM_PORT = "OZONE_LOCAL_OM_PORT";
  static final String ENV_S3G_ENABLED = "OZONE_LOCAL_S3G_ENABLED";
  static final String ENV_S3G_PORT = "OZONE_LOCAL_S3G_PORT";
  static final String ENV_RECON_ENABLED = "OZONE_LOCAL_RECON_ENABLED";
  static final String ENV_RECON_PORT = "OZONE_LOCAL_RECON_PORT";
  static final String ENV_EPHEMERAL = "OZONE_LOCAL_EPHEMERAL";
  static final String ENV_STARTUP_TIMEOUT = "OZONE_LOCAL_STARTUP_TIMEOUT";
  static final String ENV_S3_ACCESS_KEY = "OZONE_LOCAL_S3_ACCESS_KEY";
  static final String ENV_S3_SECRET_KEY = "OZONE_LOCAL_S3_SECRET_KEY";
  static final String ENV_AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";
  static final String ENV_AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

  public static void main(String[] args) {
    ExitUtils.disableSystemExit();
    new OzoneLocal().run(args);
  }

  @Command(name = "run",
      description = "Start SCM, OM, datanodes, and optional gateways in one process")
  static class RunCommand implements Callable<Void> {

    @ParentCommand
    private OzoneLocal parent;

    @Spec
    private CommandSpec spec;

    private final Map<String, String> environment;

    @Option(names = "--data-dir",
        description = "Persistent data directory for the local cluster")
    private String dataDir;

    @Option(names = "--format",
        description = "Storage init mode: if-needed, always, never")
    private String format;

    @Option(names = "--datanodes",
        description = "Number of datanodes to start")
    private Integer datanodes;

    @Option(names = "--host",
        description = "Advertised host to write into local service addresses")
    private String host;

    @Option(names = "--bind-host",
        description = "Bind host for HTTP and RPC listeners")
    private String bindHost;

    @Option(names = "--scm-port",
        description = "SCM client RPC port (0 means auto-allocate)")
    private Integer scmPort;

    @Option(names = "--om-port",
        description = "OM RPC port (0 means auto-allocate)")
    private Integer omPort;

    @Option(names = "--s3g-port",
        description = "S3 Gateway HTTP port (0 means auto-allocate)")
    private Integer s3gPort;

    @Option(names = "--recon-port",
        description = "Recon HTTP port (0 means auto-allocate)")
    private Integer reconPort;

    @Option(names = "--without-s3g",
        description = "Disable S3 Gateway")
    private boolean withoutS3g;

    @Option(names = "--with-recon",
        description = "Start Recon management and monitoring UI")
    private boolean withRecon;

    @Option(names = "--ephemeral",
        description = "Delete the data directory on shutdown")
    private boolean ephemeral;

    @Option(names = "--startup-timeout",
        description = "How long to wait for the local cluster to become ready")
    private String startupTimeout;

    @Option(names = "--s3-access-key",
        description = "Suggested local AWS access key to print on startup")
    private String s3AccessKey;

    @Option(names = "--s3-secret-key",
        description = "Suggested local AWS secret key to print on startup")
    private String s3SecretKey;

    RunCommand() {
      this(System.getenv());
    }

    RunCommand(Map<String, String> environment) {
      this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public Void call() throws Exception {
      OzoneConfiguration baseConfiguration = getBaseConfiguration();
      LocalOzoneClusterConfig config = resolveConfig(baseConfiguration);
      LocalOzoneRuntime runtime = createRuntime(config,
          new OzoneConfiguration(baseConfiguration));
      try {
        runtime.start();
        printSummary(runtime, config, getOutput());
        awaitShutdown(runtime);
      } finally {
        runtime.close();
      }
      return null;
    }

    LocalOzoneClusterConfig resolveConfig(OzoneConfiguration baseConfiguration) {
      Path resolvedDataDir = resolvePath(dataDir, environment.get(ENV_DATA_DIR),
          ENV_DATA_DIR,
          Paths.get(System.getProperty("user.home"), ".ozone", "local"));
      LocalOzoneClusterConfig.FormatMode resolvedFormat = format != null
          ? parseFormat(format, "--format")
          : parseFormat(environment.get(ENV_FORMAT), ENV_FORMAT,
              LocalOzoneClusterConfig.FormatMode.IF_NEEDED);
      int resolvedDatanodes = datanodes != null ? datanodes
          : parseInt(environment.get(ENV_DATANODES), ENV_DATANODES,
              LocalOzoneClusterConfig.DEFAULT_DATANODES);
      if (resolvedDatanodes < 1) {
        throw new IllegalArgumentException("Datanode count must be at least 1.");
      }

      String resolvedHost = firstNonBlank(host, environment.get(ENV_HOST),
          LocalOzoneClusterConfig.DEFAULT_HOST);
      String resolvedBindHost = firstNonBlank(bindHost,
          environment.get(ENV_BIND_HOST),
          LocalOzoneClusterConfig.DEFAULT_BIND_HOST);

      int resolvedScmPort = scmPort != null ? validatePort(scmPort, "--scm-port")
          : parsePort(environment.get(ENV_SCM_PORT), ENV_SCM_PORT, 0);
      int resolvedOmPort = omPort != null ? validatePort(omPort, "--om-port")
          : parsePort(environment.get(ENV_OM_PORT), ENV_OM_PORT, 0);
      int resolvedS3gPort = s3gPort != null ? validatePort(s3gPort, "--s3g-port")
          : parsePort(environment.get(ENV_S3G_PORT), ENV_S3G_PORT, 0);
      int resolvedReconPort = reconPort != null
          ? validatePort(reconPort, "--recon-port")
          : parsePort(environment.get(ENV_RECON_PORT), ENV_RECON_PORT, 0);

      boolean resolvedS3gEnabled = withoutS3g ? false
          : parseBoolean(environment.get(ENV_S3G_ENABLED), ENV_S3G_ENABLED, true);
      boolean resolvedReconEnabled = withRecon || parseBoolean(
          environment.get(ENV_RECON_ENABLED), ENV_RECON_ENABLED, false);
      boolean resolvedEphemeral = ephemeral || parseBoolean(
          environment.get(ENV_EPHEMERAL), ENV_EPHEMERAL, false);

      Duration resolvedStartupTimeout = startupTimeout != null
          ? parseDuration(startupTimeout, "--startup-timeout", baseConfiguration)
          : parseDuration(environment.get(ENV_STARTUP_TIMEOUT),
              ENV_STARTUP_TIMEOUT,
              LocalOzoneClusterConfig.DEFAULT_STARTUP_TIMEOUT,
              baseConfiguration);
      if (resolvedStartupTimeout.isZero() || resolvedStartupTimeout.isNegative()) {
        throw new IllegalArgumentException("Startup timeout must be greater than zero.");
      }

      String resolvedAccessKey = firstNonBlank(s3AccessKey,
          environment.get(ENV_S3_ACCESS_KEY),
          environment.get(ENV_AWS_ACCESS_KEY_ID),
          LocalOzoneClusterConfig.DEFAULT_S3_ACCESS_KEY);
      String resolvedSecretKey = firstNonBlank(s3SecretKey,
          environment.get(ENV_S3_SECRET_KEY),
          environment.get(ENV_AWS_SECRET_ACCESS_KEY),
          LocalOzoneClusterConfig.DEFAULT_S3_SECRET_KEY);

      return LocalOzoneClusterConfig.builder(resolvedDataDir)
          .setFormatMode(resolvedFormat)
          .setDatanodes(resolvedDatanodes)
          .setHost(resolvedHost)
          .setBindHost(resolvedBindHost)
          .setScmPort(resolvedScmPort)
          .setOmPort(resolvedOmPort)
          .setS3gPort(resolvedS3gPort)
          .setS3gEnabled(resolvedS3gEnabled)
          .setReconPort(resolvedReconPort)
          .setReconEnabled(resolvedReconEnabled)
          .setEphemeral(resolvedEphemeral)
          .setStartupTimeout(resolvedStartupTimeout)
          .setS3AccessKey(resolvedAccessKey)
          .setS3SecretKey(resolvedSecretKey)
          .build();
    }

    private static String firstNonBlank(String... values) {
      for (String value : values) {
        if (!isBlank(value)) {
          return value.trim();
        }
      }
      return null;
    }

    private static Path resolvePath(String cliValue, String envValue,
        String source, Path fallback) {
      String rawValue = firstNonBlank(cliValue, envValue, null);
      if (isBlank(rawValue)) {
        return fallback.toAbsolutePath().normalize();
      }
      try {
        return Paths.get(rawValue).toAbsolutePath().normalize();
      } catch (InvalidPathException ex) {
        throw new IllegalArgumentException("Unable to parse " + source
            + " as a filesystem path: " + rawValue, ex);
      }
    }

    private static LocalOzoneClusterConfig.FormatMode parseFormat(String rawValue,
        String source) {
      try {
        return LocalOzoneClusterConfig.FormatMode.fromString(rawValue);
      } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException("Unable to parse " + source
            + ". Expected one of: if-needed, always, never.", ex);
      }
    }

    private static LocalOzoneClusterConfig.FormatMode parseFormat(String rawValue,
        String source, LocalOzoneClusterConfig.FormatMode fallback) {
      if (isBlank(rawValue)) {
        return fallback;
      }
      return parseFormat(rawValue, source);
    }

    private static int parseInt(String rawValue, String source, int fallback) {
      if (isBlank(rawValue)) {
        return fallback;
      }
      try {
        return Integer.parseInt(rawValue.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Unable to parse " + source
            + " as an integer: " + rawValue, ex);
      }
    }

    private static boolean parseBoolean(String rawValue, String source,
        boolean fallback) {
      if (isBlank(rawValue)) {
        return fallback;
      }
      String value = rawValue.trim();
      if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
          || "on".equalsIgnoreCase(value)) {
        return true;
      }
      if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)
          || "off".equalsIgnoreCase(value)) {
        return false;
      }
      throw new IllegalArgumentException("Unable to parse " + source
          + " as a boolean. Use true/false, yes/no, or on/off.");
    }

    private static int parsePort(String rawValue, String source, int fallback) {
      return isBlank(rawValue) ? fallback
          : validatePort(parseInt(rawValue, source, fallback), source);
    }

    private static int validatePort(int value, String source) {
      if (value < 0 || value > 65_535) {
        throw new IllegalArgumentException("Port value for " + source
            + " must be between 0 and 65535.");
      }
      return value;
    }

    private static Duration parseDuration(String rawValue, String source,
        Duration fallback, OzoneConfiguration baseConfiguration) {
      if (isBlank(rawValue)) {
        return fallback;
      }
      return parseDuration(rawValue, source, baseConfiguration);
    }

    private static Duration parseDuration(String rawValue, String source,
        OzoneConfiguration baseConfiguration) {
      try {
        return Duration.parse(rawValue.trim());
      } catch (DateTimeParseException ignored) {
        OzoneConfiguration conf = new OzoneConfiguration(baseConfiguration);
        String configKey = "ozone.local.duration.parse";
        conf.set(configKey, rawValue.trim());
        try {
          long millis = conf.getTimeDuration(configKey, -1L,
              TimeUnit.MILLISECONDS);
          if (millis < 0) {
            throw new IllegalArgumentException("Unable to parse " + source
                + " as a duration. Use ISO-8601 like PT2M or Hadoop-style values like 120s.");
          }
          return Duration.ofMillis(millis);
        } catch (RuntimeException ex) {
          throw new IllegalArgumentException("Unable to parse " + source
              + " as a duration. Use ISO-8601 like PT2M or Hadoop-style values like 120s.",
              ex);
        }
      }
    }

    private static boolean isBlank(String value) {
      if (value == null) {
        return true;
      }
      for (int index = 0; index < value.length(); index++) {
        if (!Character.isWhitespace(value.charAt(index))) {
          return false;
        }
      }
      return true;
    }

    LocalOzoneRuntime createRuntime(LocalOzoneClusterConfig config,
        OzoneConfiguration baseConfiguration) {
      return new LocalOzoneCluster(config, baseConfiguration);
    }

    void awaitShutdown(LocalOzoneRuntime runtime) throws InterruptedException {
      CountDownLatch stopped = new CountDownLatch(1);
      Thread shutdownHook = new Thread(() -> {
        try {
          runtime.close();
        } catch (Exception ex) {
          throw new RuntimeException("Failed to close ozone local runtime",
              ex);
        } finally {
          stopped.countDown();
        }
      }, "ozone-local-shutdown");
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      try {
        stopped.await();
      } finally {
        try {
          Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
          // JVM shutdown is already in progress.
        }
      }
    }

    List<String> buildSummary(LocalOzoneRuntime runtime,
        LocalOzoneClusterConfig config) {
      List<String> lines = new ArrayList<>();
      lines.add("Local Ozone is running from " + config.getDataDir());
      lines.add("SCM RPC: " + runtime.getDisplayHost() + ":"
          + runtime.getScmPort());
      lines.add("OM RPC: " + runtime.getDisplayHost() + ":"
          + runtime.getOmPort());
      if (config.isS3gEnabled()) {
        lines.add("S3 endpoint: " + runtime.getS3Endpoint());
        lines.add("Suggested local AWS settings:");
        lines.add("AWS_ACCESS_KEY_ID=" + config.getS3AccessKey());
        lines.add("AWS_SECRET_ACCESS_KEY=" + config.getS3SecretKey());
        lines.add("AWS_REGION=" + config.getS3Region());
        lines.add("AWS_ENDPOINT_URL_S3=" + runtime.getS3Endpoint());
        lines.add("AWS_S3_FORCE_PATH_STYLE=true");
        lines.add("These credentials are pre-provisioned for the local S3 "
            + "gateway.");
      }
      if (config.isReconEnabled()) {
        lines.add("Recon endpoint: " + runtime.getReconEndpoint());
      }
      lines.add("Press Ctrl+C to stop.");
      return lines;
    }

    void printSummary(LocalOzoneRuntime runtime, LocalOzoneClusterConfig config,
        PrintWriter out) {
      buildSummary(runtime, config).forEach(out::println);
      out.flush();
    }

    private OzoneConfiguration getBaseConfiguration() {
      return parent != null ? parent.getOzoneConf() : new OzoneConfiguration();
    }

    private PrintWriter getOutput() {
      return spec != null ? spec.commandLine().getOut()
          : new PrintWriter(new OutputStreamWriter(System.out,
              StandardCharsets.UTF_8), true);
    }
  }
}
