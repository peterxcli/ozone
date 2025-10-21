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

package org.apache.ozone.admin;

import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for managing remote volumes.
 */
public class RemoteVolumeService {

  private static final Logger LOG = LoggerFactory.getLogger(RemoteVolumeService.class);
  private static final String REMOTE_VOLUME_PREFIX = "remote_volume_";
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final OzoneConfiguration configuration;
  private final ObjectStore objectStore;
  private final RemoteVolumeCatalog catalog;

  public RemoteVolumeService(OzoneConfiguration configuration, ObjectStore objectStore) {
    this.configuration = configuration;
    this.objectStore = objectStore;
    this.catalog = new RemoteVolumeCatalog(configuration);
  }

  /**
   * Create a remote volume with all necessary resources.
   */
  public CreateRemoteVolumeResponse createRemoteVolume(CreateRemoteVolumeRequest request)
      throws Exception {

    String volumeName = REMOTE_VOLUME_PREFIX + sanitizeName(request.getRemoteVolumeName());
    String remoteVolumeId = UUID.randomUUID().toString();

    LOG.info("Creating remote volume: {} with ID: {}", volumeName, remoteVolumeId);

    CreateRemoteVolumeResponse response = new CreateRemoteVolumeResponse();
    response.setRemoteVolumeId(remoteVolumeId);
    response.setRemoteVolumeName(request.getRemoteVolumeName());
    response.setOwnerId(request.getOwnerId());

    try {
      // Step 1: Create metadata store database (if internal)
      String metadataConnectionString;
      if (request.getMetadataStoreSource() == MetadataStoreSource.INTERNAL) {
        metadataConnectionString = createMetadataDatabase(volumeName);
        LOG.info("Created internal metadata database for volume: {}", volumeName);
      } else {
        // External: use provided connection string
        metadataConnectionString = request.getMetadataStoreInfo().getConnectionString();
        LOG.info("Using external metadata database for volume: {}", volumeName);
      }
      response.setMetadataConnectionString(metadataConnectionString);

      // Step 2: Create bucket and user with access keys
      BucketCredentials credentials = createBucketAndCredentials(volumeName, request.getOwnerId());
      response.setBucketName(credentials.getBucketName());
      response.setAccessKey(credentials.getAccessKey());
      response.setSecretKey(credentials.getSecretKey());
      LOG.info("Created bucket and credentials for volume: {}", volumeName);

      // Step 3: Format JuiceFS filesystem
      formatJuiceFS(volumeName, metadataConnectionString, credentials,
          request.getAdditionalJuiceFsCommand());
      LOG.info("Formatted JuiceFS filesystem for volume: {}", volumeName);

      // Step 4: Record in catalog
      catalog.recordRemoteVolume(remoteVolumeId, request, response);
      LOG.info("Recorded remote volume in catalog: {}", volumeName);

      response.setStatus("success");
      response.setMessage("Remote volume created successfully");
      return response;

    } catch (RemoteVolumeAlreadyExistsException e) {
      LOG.error("Remote volume already exists: {}", volumeName, e);
      throw e;
    } catch (Exception e) {
      LOG.error("Failed to create remote volume: {}", volumeName, e);
      response.setStatus("error");
      response.setMessage("Failed to create remote volume: " + e.getMessage());

      // Cleanup on failure
      try {
        cleanupFailedVolume(volumeName, request.getMetadataStoreSource());
      } catch (Exception cleanupEx) {
        LOG.error("Error during cleanup: {}", cleanupEx.getMessage(), cleanupEx);
      }

      throw e;
    }
  }

  /**
   * Create metadata database and user for internal metadata store.
   */
  private String createMetadataDatabase(String volumeName)
      throws SQLException, RemoteVolumeAlreadyExistsException {

    String dbHost = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_HOST, "localhost");
    int dbPort = configuration.getInt(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_PORT, 5432);
    String adminUser = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_USERNAME, "postgres");
    String adminPassword = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_PASSWORD, "");

    String dbName = volumeName;
    String dbUser = volumeName + "_user";
    String dbPassword = generateSecurePassword();

    String jdbcUrl = String.format("jdbc:postgresql://%s:%d/postgres", dbHost, dbPort);

    try (Connection conn = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
         Statement stmt = conn.createStatement()) {

      // Check if database exists
      String checkDbQuery = String.format(
          "SELECT 1 FROM pg_database WHERE datname='%s'", dbName);
      try (ResultSet rs = stmt.executeQuery(checkDbQuery)) {
        if (rs.next()) {
          throw new RemoteVolumeAlreadyExistsException(
              "Database already exists: " + dbName);
        }
      }

      // Create user
      String createUserSQL = String.format(
          "CREATE USER %s WITH PASSWORD '%s'", dbUser, dbPassword);
      stmt.execute(createUserSQL);
      LOG.info("Created database user: {}", dbUser);

      // Create database with owner
      String createDbSQL = String.format(
          "CREATE DATABASE %s OWNER %s", dbName, dbUser);
      stmt.execute(createDbSQL);
      LOG.info("Created database: {}", dbName);

      // Return connection string
      return String.format("postgres://%s:%s@%s:%d/%s",
          dbUser, dbPassword, dbHost, dbPort, dbName);

    } catch (SQLException e) {
      if (e.getMessage().contains("already exists")) {
        throw new RemoteVolumeAlreadyExistsException(
            "Database already exists: " + dbName, e);
      }
      throw e;
    }
  }

  /**
   * Create Ozone bucket and generate S3 credentials.
   */
  private BucketCredentials createBucketAndCredentials(String volumeName, String ownerId)
      throws IOException {

    String bucketName = volumeName;

    // Create S3 bucket
    try {
      objectStore.createS3Bucket(bucketName);
      LOG.info("Created S3 bucket: {}", bucketName);
    } catch (IOException e) {
      if (e.getMessage() != null && e.getMessage().contains("already exists")) {
        throw new IOException("Bucket already exists: " + bucketName, e);
      }
      throw e;
    }

    // Generate access keys
    // Note: In a real implementation, you would use Ozone's S3 secret manager
    // to create proper AWS-compatible access keys. This is a simplified version.
    String accessKey = generateAccessKey(ownerId);
    String secretKey = generateSecretKey();

    BucketCredentials credentials = new BucketCredentials();
    credentials.setBucketName(bucketName);
    credentials.setAccessKey(accessKey);
    credentials.setSecretKey(secretKey);

    return credentials;
  }

  /**
   * Format JuiceFS filesystem.
   */
  private void formatJuiceFS(String volumeName, String metadataUrl,
      BucketCredentials credentials, String additionalCommands) throws IOException {

    ProcessBuilder pb = new ProcessBuilder();
    pb.command().add("juicefs");
    pb.command().add("format");
    pb.command().add("--storage");
    pb.command().add("s3");
    pb.command().add("--bucket");
    pb.command().add(getS3BucketUrl(credentials.getBucketName()));
    pb.command().add("--access-key");
    pb.command().add(credentials.getAccessKey());
    pb.command().add("--secret-key");
    pb.command().add(credentials.getSecretKey());

    // Add additional commands if provided
    if (additionalCommands != null && !additionalCommands.trim().isEmpty()) {
      String[] extraArgs = additionalCommands.trim().split("\\s+");
      for (String arg : extraArgs) {
        pb.command().add(arg);
      }
    }

    pb.command().add(metadataUrl);
    pb.command().add(volumeName);

    LOG.info("Executing JuiceFS format command: {}", String.join(" ", pb.command()));

    Process process = pb.start();
    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IOException("JuiceFS format failed with exit code: " + exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("JuiceFS format interrupted", e);
    }
  }

  /**
   * Get S3 bucket URL from configuration.
   */
  private String getS3BucketUrl(String bucketName) {
    String s3Endpoint = configuration.get("ozone.s3gateway.http.address", "localhost:9878");
    return String.format("http://%s/%s", s3Endpoint, bucketName);
  }

  /**
   * Clean up resources on failure.
   */
  private void cleanupFailedVolume(String volumeName, MetadataStoreSource source) {
    LOG.info("Cleaning up failed volume: {}", volumeName);

    // Try to delete bucket
    try {
      objectStore.deleteS3Bucket(volumeName);
      LOG.info("Deleted bucket: {}", volumeName);
    } catch (Exception e) {
      LOG.warn("Failed to cleanup bucket: {}", e.getMessage());
    }

    // Try to delete database (if internal)
    if (source == MetadataStoreSource.INTERNAL) {
      try {
        dropMetadataDatabase(volumeName);
        LOG.info("Dropped database: {}", volumeName);
      } catch (Exception e) {
        LOG.warn("Failed to cleanup database: {}", e.getMessage());
      }
    }
  }

  /**
   * Drop metadata database.
   */
  private void dropMetadataDatabase(String volumeName) throws SQLException {
    String dbHost = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_HOST, "localhost");
    int dbPort = configuration.getInt(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_PORT, 5432);
    String adminUser = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_USERNAME, "postgres");
    String adminPassword = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_PASSWORD, "");

    String jdbcUrl = String.format("jdbc:postgresql://%s:%d/postgres", dbHost, dbPort);
    String dbUser = volumeName + "_user";

    try (Connection conn = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
         Statement stmt = conn.createStatement()) {

      // Drop database
      stmt.execute(String.format("DROP DATABASE IF EXISTS %s", volumeName));

      // Drop user
      stmt.execute(String.format("DROP USER IF EXISTS %s", dbUser));
    }
  }

  /**
   * Sanitize name for use in database/bucket names.
   */
  private String sanitizeName(String name) {
    return name.toLowerCase()
        .replaceAll("[^a-z0-9-]", "-")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "");
  }

  /**
   * Generate secure password.
   */
  private String generateSecurePassword() {
    byte[] randomBytes = new byte[32];
    SECURE_RANDOM.nextBytes(randomBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
  }

  /**
   * Generate access key.
   */
  private String generateAccessKey(String ownerId) {
    return String.format("AKOA%s%08X",
        ownerId.substring(0, Math.min(4, ownerId.length())).toUpperCase(),
        SECURE_RANDOM.nextInt());
  }

  /**
   * Generate secret key.
   */
  private String generateSecretKey() {
    byte[] randomBytes = new byte[40];
    SECURE_RANDOM.nextBytes(randomBytes);
    return Base64.getEncoder().encodeToString(randomBytes);
  }

  /**
   * Bucket credentials holder.
   */
  private static class BucketCredentials {
    private String bucketName;
    private String accessKey;
    private String secretKey;

    public String getBucketName() {
      return bucketName;
    }

    public void setBucketName(String bucketName) {
      this.bucketName = bucketName;
    }

    public String getAccessKey() {
      return accessKey;
    }

    public void setAccessKey(String accessKey) {
      this.accessKey = accessKey;
    }

    public String getSecretKey() {
      return secretKey;
    }

    public void setSecretKey(String secretKey) {
      this.secretKey = secretKey;
    }
  }
}
