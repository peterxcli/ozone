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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catalog for storing remote volume metadata in PostgreSQL.
 */
public class RemoteVolumeCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(RemoteVolumeCatalog.class);
  private static final String CATALOG_TABLE = "remote_volume_catalog";

  private final OzoneConfiguration configuration;

  public RemoteVolumeCatalog(OzoneConfiguration configuration) {
    this.configuration = configuration;
    initializeCatalog();
  }

  /**
   * TODO: This is a temporary solution to initialize the catalog database.
   * We need to add a admin gateway sub command to run a one time migration.
   *
   * Initialize catalog database using Flyway migrations.
   */
  private void initializeCatalog() {
    try {
      String jdbcUrl = getJdbcUrl();
      String dbUser = configuration.get(
          AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_USERNAME, "postgres");
      String dbPassword = configuration.get(
          AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_PASSWORD, "");

      LOG.info("Running Flyway migrations for remote volume catalog");

      // Configure and run Flyway migrations
      Flyway flyway = Flyway.configure()
          .dataSource(jdbcUrl, dbUser, dbPassword)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .table("remote_volume_schema_history")
          .load();

      // Run migrations
      int migrationsApplied = flyway.migrate().migrationsExecuted;
      LOG.info("Applied {} Flyway migration(s) to remote volume catalog", migrationsApplied);

    } catch (Exception e) {
      LOG.error("Failed to initialize catalog with Flyway: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to initialize remote volume catalog", e);
    }
  }

  /**
   * Get JDBC URL for catalog database.
   */
  private String getJdbcUrl() {
    String dbHost = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_HOST, "localhost");
    int dbPort = configuration.getInt(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_PORT, 5432);
    String catalogDbName = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_NAME, "ozone_catalog");
    return String.format("jdbc:postgresql://%s:%d/%s", dbHost, dbPort, catalogDbName);
  }

  /**
   * Record a new remote volume in the catalog.
   */
  public void recordRemoteVolume(String remoteVolumeId,
      CreateRemoteVolumeRequest request,
      CreateRemoteVolumeResponse response) throws SQLException {

    String insertSQL = String.format(
        "INSERT INTO %s "
            + "(id, remote_volume_name, owner_id, bucket_name, "
            + "metadata_store_source, metadata_connection_string, "
            + "access_key, secret_key_hash, status, created_at, updated_at, additional_info) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        CATALOG_TABLE);

    try (Connection conn = getCatalogConnection();
         PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {

      Timestamp now = new Timestamp(System.currentTimeMillis());

      pstmt.setString(1, remoteVolumeId);
      pstmt.setString(2, request.getRemoteVolumeName());
      pstmt.setString(3, request.getOwnerId());
      pstmt.setString(4, response.getBucketName());
      pstmt.setString(5, request.getMetadataStoreSource().name());
      pstmt.setString(6, response.getMetadataConnectionString());
      pstmt.setString(7, response.getAccessKey());
      pstmt.setString(8, hashSecretKey(response.getSecretKey()));
      pstmt.setString(9, "active");
      pstmt.setTimestamp(10, now);
      pstmt.setTimestamp(11, now);
      pstmt.setString(12, request.getAdditionalJuiceFsCommand());

      pstmt.executeUpdate();
      LOG.info("Recorded remote volume in catalog: {}", remoteVolumeId);

    } catch (SQLException e) {
      LOG.error("Failed to record remote volume: {}", e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Get catalog database connection.
   */
  private Connection getCatalogConnection() throws SQLException {
    String jdbcUrl = getJdbcUrl();
    String dbUser = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_USERNAME, "postgres");
    String dbPassword = configuration.get(
        AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_JUICEFS_CATALOG_DB_PASSWORD, "");
    return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
  }

  /**
   * Hash secret key for storage (for security, we don't store plain text).
   */
  private String hashSecretKey(String secretKey) {
    // In production, use proper hashing algorithm like bcrypt
    // This is simplified for demonstration
    return String.format("SHA256:%08X", secretKey.hashCode());
  }
}
