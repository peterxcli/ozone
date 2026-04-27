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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

/**
 * AWS SDK integration tests for {@link LocalOzoneCluster}.
 */
class TestLocalOzoneS3SDK {

  @TempDir
  private Path tempDir;

  @Test
  void awsSdkCanCreateListPutAndGetAgainstLocalRuntime() throws Exception {
    LocalOzoneClusterConfig config = LocalOzoneClusterConfig.builder(
            tempDir.resolve("local-ozone-s3"))
        .setDatanodes(1)
        .setS3AccessKey("user")
        .setS3SecretKey("password")
        .setStartupTimeout(Duration.ofMinutes(3))
        .build();

    String bucketName = "local-" + UUID.randomUUID().toString()
        .replace("-", "");
    String keyName = "key-" + UUID.randomUUID().toString().replace("-", "");
    String payload = "local-ozone-s3";

    try (LocalOzoneCluster cluster =
             new LocalOzoneCluster(config, new OzoneConfiguration())) {
      cluster.start();

      S3Client client = S3Client.builder()
          .region(Region.US_EAST_1)
          .endpointOverride(URI.create(cluster.getS3Endpoint()))
          .credentialsProvider(StaticCredentialsProvider.create(
              AwsBasicCredentials.create(config.getS3AccessKey(),
                  config.getS3SecretKey())))
          .forcePathStyle(true)
          .build();
      try {
        client.createBucket(builder -> builder.bucket(bucketName));

        ListBucketsResponse buckets = client.listBuckets();
        assertTrue(buckets.buckets().stream()
            .anyMatch(bucket -> bucketName.equals(bucket.name())));

        client.putObject(builder -> builder.bucket(bucketName).key(keyName),
            RequestBody.fromString(payload));

        ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(
            builder -> builder.bucket(bucketName).key(keyName));
        assertEquals(payload, response.asUtf8String());
      } finally {
        client.close();
      }
    }
  }
}
