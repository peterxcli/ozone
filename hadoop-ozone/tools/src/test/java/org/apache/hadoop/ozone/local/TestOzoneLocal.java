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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void runCommandMetadataIsPresentAndHidden() {
    Command command = OzoneLocal.RunCommand.class.getAnnotation(Command.class);

    assertNotNull(command);
    assertEquals("run", command.name());
    assertTrue(command.hidden());
  }

  @Test
  void runCommandIsANoOpPlaceholder() {
    int exitCode = new CommandLine(new OzoneLocal.RunCommand()).execute();

    assertEquals(0, exitCode);
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
    assertEquals(Duration.ofSeconds(120), config.getStartupTimeout());
    assertEquals("dev", config.getS3AccessKey());
    assertEquals("devsecret", config.getS3SecretKey());
    assertTrue(config.isEphemeral());
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
}
