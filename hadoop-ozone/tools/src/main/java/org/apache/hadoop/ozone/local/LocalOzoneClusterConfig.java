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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Configuration for a single-node local Ozone cluster.
 */
public final class LocalOzoneClusterConfig {

  static final String DEFAULT_HOST = "127.0.0.1";
  static final String DEFAULT_BIND_HOST = "0.0.0.0";
  static final int DEFAULT_DATANODES = 1;
  static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(2);
  static final String DEFAULT_S3_ACCESS_KEY = "admin";
  static final String DEFAULT_S3_SECRET_KEY = "admin123";
  static final String DEFAULT_S3_REGION = "us-east-1";

  private final Path dataDir;
  private final FormatMode formatMode;
  private final int datanodes;
  private final boolean ephemeral;
  private final boolean s3gEnabled;
  private final String host;
  private final String bindHost;
  private final int scmPort;
  private final int omPort;
  private final int s3gPort;
  private final Duration startupTimeout;
  private final String s3AccessKey;
  private final String s3SecretKey;
  private final String s3Region;

  private LocalOzoneClusterConfig(Builder builder) {
    this.dataDir = Objects.requireNonNull(builder.dataDir, "dataDir");
    this.formatMode = Objects.requireNonNull(builder.formatMode, "formatMode");
    this.datanodes = builder.datanodes;
    this.ephemeral = builder.ephemeral;
    this.s3gEnabled = builder.s3gEnabled;
    this.host = Objects.requireNonNull(builder.host, "host");
    this.bindHost = Objects.requireNonNull(builder.bindHost, "bindHost");
    this.scmPort = builder.scmPort;
    this.omPort = builder.omPort;
    this.s3gPort = builder.s3gPort;
    this.startupTimeout = Objects.requireNonNull(builder.startupTimeout,
        "startupTimeout");
    this.s3AccessKey = Objects.requireNonNull(builder.s3AccessKey,
        "s3AccessKey");
    this.s3SecretKey = Objects.requireNonNull(builder.s3SecretKey,
        "s3SecretKey");
    this.s3Region = Objects.requireNonNull(builder.s3Region, "s3Region");
  }

  public Path getDataDir() {
    return dataDir;
  }

  public FormatMode getFormatMode() {
    return formatMode;
  }

  public int getDatanodes() {
    return datanodes;
  }

  public boolean isEphemeral() {
    return ephemeral;
  }

  public boolean isS3gEnabled() {
    return s3gEnabled;
  }

  public String getHost() {
    return host;
  }

  public String getBindHost() {
    return bindHost;
  }

  public int getScmPort() {
    return scmPort;
  }

  public int getOmPort() {
    return omPort;
  }

  public int getS3gPort() {
    return s3gPort;
  }

  public Duration getStartupTimeout() {
    return startupTimeout;
  }

  public String getS3AccessKey() {
    return s3AccessKey;
  }

  public String getS3SecretKey() {
    return s3SecretKey;
  }

  public String getS3Region() {
    return s3Region;
  }

  public static Builder builder(Path dataDir) {
    return new Builder(dataDir);
  }

  /**
   * Storage initialization mode.
   */
  public enum FormatMode {
    IF_NEEDED,
    ALWAYS,
    NEVER;

    public static FormatMode fromString(String value) {
      return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
  }

  /**
   * Builder for {@link LocalOzoneClusterConfig}.
   */
  public static final class Builder {

    private final Path dataDir;
    private FormatMode formatMode = FormatMode.IF_NEEDED;
    private int datanodes = DEFAULT_DATANODES;
    private boolean ephemeral;
    private boolean s3gEnabled = true;
    private String host = DEFAULT_HOST;
    private String bindHost = DEFAULT_BIND_HOST;
    private int scmPort;
    private int omPort;
    private int s3gPort;
    private Duration startupTimeout = DEFAULT_STARTUP_TIMEOUT;
    private String s3AccessKey = DEFAULT_S3_ACCESS_KEY;
    private String s3SecretKey = DEFAULT_S3_SECRET_KEY;
    private String s3Region = DEFAULT_S3_REGION;

    private Builder(Path dataDir) {
      this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    public Builder setFormatMode(FormatMode value) {
      this.formatMode = value;
      return this;
    }

    public Builder setDatanodes(int value) {
      this.datanodes = value;
      return this;
    }

    public Builder setEphemeral(boolean value) {
      this.ephemeral = value;
      return this;
    }

    public Builder setS3gEnabled(boolean value) {
      this.s3gEnabled = value;
      return this;
    }

    public Builder setHost(String value) {
      this.host = value;
      return this;
    }

    public Builder setBindHost(String value) {
      this.bindHost = value;
      return this;
    }

    public Builder setScmPort(int value) {
      this.scmPort = value;
      return this;
    }

    public Builder setOmPort(int value) {
      this.omPort = value;
      return this;
    }

    public Builder setS3gPort(int value) {
      this.s3gPort = value;
      return this;
    }

    public Builder setStartupTimeout(Duration value) {
      this.startupTimeout = value;
      return this;
    }

    public Builder setS3AccessKey(String value) {
      this.s3AccessKey = value;
      return this;
    }

    public Builder setS3SecretKey(String value) {
      this.s3SecretKey = value;
      return this;
    }

    public Builder setS3Region(String value) {
      this.s3Region = value;
      return this;
    }

    public LocalOzoneClusterConfig build() {
      return new LocalOzoneClusterConfig(this);
    }
  }
}
