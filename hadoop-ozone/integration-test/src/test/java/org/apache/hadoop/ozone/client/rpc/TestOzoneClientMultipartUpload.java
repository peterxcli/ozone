/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.client.rpc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;

import javax.xml.bind.DatatypeConverter;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.client.DefaultReplicationConfig;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.protocolPB.StorageContainerLocationProtocolClientSideTranslatorPB;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.OzoneTestUtils;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.client.BucketArgs;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneKeyDetails;
import org.apache.hadoop.ozone.client.OzoneKeyLocation;
import org.apache.hadoop.ozone.client.OzoneMultipartUpload;
import org.apache.hadoop.ozone.client.OzoneMultipartUploadList;
import org.apache.hadoop.ozone.client.OzoneMultipartUploadPartListParts;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.ResolvedBucket;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OzoneFSUtils;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.om.helpers.OmMultipartCommitUploadPartInfo;
import org.apache.hadoop.ozone.om.helpers.OmMultipartInfo;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmMultipartKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmMultipartUploadCompleteInfo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.StringUtils.string2Bytes;
import static org.apache.hadoop.hdds.client.ReplicationFactor.THREE;

import org.apache.hadoop.ozone.om.helpers.QuotaUtil;
import org.apache.hadoop.ozone.om.request.OMRequestTestUtils;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.apache.hadoop.ozone.security.acl.OzoneObjInfo;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLType;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLIdentityType;
import org.apache.hadoop.ozone.OzoneAcl.AclScope;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.apache.hadoop.hdds.client.ReplicationFactor.ONE;
import static org.apache.hadoop.hdds.client.ReplicationType.RATIS;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_RATIS_PIPELINE_LIMIT;
import static org.apache.hadoop.ozone.OzoneConsts.ETAG;
import static org.apache.hadoop.ozone.OmUtils.LOG;
import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.NO_SUCH_MULTIPART_UPLOAD_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies all the S3 multipart client apis - prefix layout.
 */
@Timeout(300)
public class TestOzoneClientMultipartUpload {

  private static ObjectStore store = null;
  private static MiniOzoneCluster cluster = null;
  private static OzoneClient ozClient = null;
  private static OzoneManager ozoneManager;
  private static StorageContainerLocationProtocolClientSideTranslatorPB
      storageContainerLocationClient;
  private static MessageDigest eTagProvider;

  private String volumeName;
  private String bucketName;
  private String keyName;
  private OzoneVolume volume;

  /**
   * Create a MiniOzoneCluster for testing.
   * <p>
   * Ozone is made active by setting OZONE_ENABLED = true
   *
   * @throws IOException
   */
  @BeforeAll
  public static void init() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setInt(OZONE_SCM_RATIS_PIPELINE_LIMIT, 10);
    OMRequestTestUtils.configureFSOptimizedPaths(conf, true);
    startCluster(conf);
    eTagProvider = MessageDigest.getInstance(OzoneConsts.MD5_HASH);
  }

  /**
   * Close OzoneClient and shutdown MiniOzoneCluster.
   */
  @AfterAll
  public static void shutdown() throws IOException {
    shutdownCluster();
  }

  /**
   * Create a MiniOzoneCluster for testing.
   * 
   * @param conf Configurations to start the cluster.
   * @throws Exception
   */
  static void startCluster(OzoneConfiguration conf) throws Exception {
    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(5)
        .build();
    cluster.waitForClusterToBeReady();
    ozClient = OzoneClientFactory.getRpcClient(conf);
    store = ozClient.getObjectStore();
    ozoneManager = cluster.getOzoneManager();
    storageContainerLocationClient = cluster.getStorageContainerLocationClient();
  }

  /**
   * Close OzoneClient and shutdown MiniOzoneCluster.
   */
  static void shutdownCluster() throws IOException {
    if (ozClient != null) {
      ozClient.close();
    }

    if (storageContainerLocationClient != null) {
      storageContainerLocationClient.close();
    }

    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @BeforeEach
  public void preTest() throws Exception {
    volumeName = UUID.randomUUID().toString();
    bucketName = UUID.randomUUID().toString();
    keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    volume = store.getVolume(volumeName);
  }

  static Stream<ReplicationConfig> replicationConfigs() {
    return Stream.of(
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE),
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.THREE),
        new ECReplicationConfig(3, 2));
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testInitiateMultipartUpload(ReplicationConfig replicationConfig)
      throws IOException {
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replicationConfig);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotNull(multipartInfo.getUploadID());

    // Call initiate multipart upload for the same key again, this should
    // generate a new uploadID.
    multipartInfo = bucket.initiateMultipartUpload(keyName,
        replicationConfig);

    assertNotNull(multipartInfo);
    assertNotEquals(multipartInfo.getUploadID(), uploadID);
    assertNotNull(multipartInfo.getUploadID());
  }

  static Stream<BucketLayout> bucketLayouts() {
    return Stream.of(
        BucketLayout.OBJECT_STORE,
        BucketLayout.LEGACY,
        BucketLayout.FILE_SYSTEM_OPTIMIZED);
  }

  BucketArgs getBucketArgs(BucketLayout bucketLayout) {
    return BucketArgs.newBuilder().setBucketLayout(bucketLayout).build();
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testInitiateMultipartUploadWithDefaultReplication(BucketLayout bucketLayout) throws IOException {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertNotNull(multipartInfo.getUploadID());

    // Call initiate multipart upload for the same key again, this should
    // generate a new uploadID.
    multipartInfo = bucket.initiateMultipartUpload(keyName);

    assertNotNull(multipartInfo);
    assertNotEquals(multipartInfo.getUploadID(), uploadID);
    assertNotNull(multipartInfo.getUploadID());
  }

  static Stream<Arguments> uploadPartWithNoOverride() {
    return bucketLayouts()
        .flatMap(bucketLayout -> replicationConfigs().map(replication -> Arguments.of(bucketLayout, replication)));
  }

  @ParameterizedTest
  @MethodSource("uploadPartWithNoOverride")
  void testUploadPartWithNoOverride(BucketLayout bucketLayout, ReplicationConfig replication)
      throws IOException {
    String sampleData = "sample Value";

    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replication);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertNotNull(multipartInfo.getUploadID());

    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        sampleData.length(), 1, uploadID);
    ozoneOutputStream.write(string2Bytes(sampleData), 0, sampleData.length());
    ozoneOutputStream.getMetadata().put(ETAG, DigestUtils.md5Hex(sampleData));
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo commitUploadPartInfo = ozoneOutputStream
        .getCommitUploadPartInfo();

    assertNotNull(commitUploadPartInfo);
    assertNotNull(commitUploadPartInfo.getETag());
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testUploadPartOverride(ReplicationConfig replication)
      throws IOException {

    String sampleData = "sample Value";
    int partNumber = 1;

    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replication);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertNotNull(multipartInfo.getUploadID());

    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        sampleData.length(), partNumber, uploadID);
    ozoneOutputStream.write(string2Bytes(sampleData), 0, sampleData.length());
    ozoneOutputStream.getMetadata().put(ETAG, DigestUtils.md5Hex(sampleData));
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo commitUploadPartInfo = ozoneOutputStream
        .getCommitUploadPartInfo();

    assertNotNull(commitUploadPartInfo);
    String partName = commitUploadPartInfo.getPartName();
    assertNotNull(commitUploadPartInfo.getETag());

    // Overwrite the part by creating part key with same part number
    // and different content.
    sampleData = "sample Data Changed";
    ozoneOutputStream = bucket.createMultipartKey(keyName,
        sampleData.length(), partNumber, uploadID);
    ozoneOutputStream.write(string2Bytes(sampleData), 0, "name".length());
    ozoneOutputStream.getMetadata().put(ETAG, DigestUtils.md5Hex(sampleData));
    ozoneOutputStream.close();

    commitUploadPartInfo = ozoneOutputStream
        .getCommitUploadPartInfo();

    assertNotNull(commitUploadPartInfo);
    assertNotNull(commitUploadPartInfo.getETag());

    // AWS S3 for same content generates same partName during upload part.
    // In AWS S3 ETag is generated from md5sum. In Ozone right now we
    // don't do this. For now to make things work for large file upload
    // through aws s3 cp, the partName are generated in a predictable fashion.
    // So, when a part is override partNames will still be same irrespective
    // of content in ozone s3. This will make S3 Mpu completeMPU pass when
    // comparing part names and large file uploads work using aws cp.
    assertEquals(partName, commitUploadPartInfo.getPartName(),
        "Part names should be same");
  }

  private static ReplicationConfig anyReplication() {
    return RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testInitiateMultipartUploadWithReplicationInformationSet(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    // Call initiate multipart upload for the same key again, this should
    // generate a new uploadID.
    String uploadIDNew = initiateMultipartUpload(bucket, keyName, replication);
    assertNotEquals(uploadIDNew, uploadID);
  }

  @Test
  public void testNoSuchUploadError() throws Exception {
    String sampleData = "sample Value";

    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String uploadID = "random";
    OzoneTestUtils
        .expectOmException(NO_SUCH_MULTIPART_UPLOAD_ERROR, () -> bucket
            .createMultipartKey(keyName, sampleData.length(), 1, uploadID));
  }

  @Test
  public void testMultipartUploadWithACL() throws Exception {
    // TODO: HDDS-3402. Files/dirs in FSO buckets currently do not inherit
    // parent ACLs.
    volume.createBucket(bucketName, getBucketArgs(BucketLayout.OBJECT_STORE));
    OzoneBucket bucket = volume.getBucket(bucketName);

    // Add ACL on Bucket
    OzoneAcl acl1 = new OzoneAcl(ACLIdentityType.USER, "Monday", AclScope.DEFAULT, ACLType.ALL);
    OzoneAcl acl2 = new OzoneAcl(ACLIdentityType.USER, "Friday", AclScope.DEFAULT, ACLType.ALL);
    OzoneAcl acl3 = new OzoneAcl(ACLIdentityType.USER, "Jan", AclScope.ACCESS, ACLType.ALL);
    OzoneAcl acl4 = new OzoneAcl(ACLIdentityType.USER, "Feb", AclScope.ACCESS, ACLType.ALL);
    bucket.addAcl(acl1);
    bucket.addAcl(acl2);
    bucket.addAcl(acl3);
    bucket.addAcl(acl4);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(
        HddsProtos.ReplicationFactor.ONE);
    doMultipartUpload(bucket, keyName, (byte) 98, replication);
    OzoneObj keyObj = OzoneObjInfo.Builder.newBuilder()
        .setBucketName(bucketName)
        .setVolumeName(volumeName).setKeyName(keyName)
        .setResType(OzoneObj.ResourceType.KEY)
        .setStoreType(OzoneObj.StoreType.OZONE).build();
    List<OzoneAcl> aclList = store.getAcl(keyObj);
    // key should inherit bucket's DEFAULT type acl
    assertTrue(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl1.getName())));
    assertTrue(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl2.getName())));

    // kye should not inherit bucket's ACCESS type acl
    assertFalse(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl3.getName())));
    assertFalse(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl4.getName())));

    // User without permission should fail to upload the object
    String userName = "test-user";
    UserGroupInformation remoteUser = UserGroupInformation.createRemoteUser(userName);
    try (OzoneClient client = remoteUser
        .doAs((PrivilegedExceptionAction<OzoneClient>) () -> OzoneClientFactory.getRpcClient(cluster.getConf()))) {
      OzoneAcl acl5 = new OzoneAcl(ACLIdentityType.USER, userName, AclScope.DEFAULT, ACLType.READ);
      OzoneAcl acl6 = new OzoneAcl(ACLIdentityType.USER, userName, AclScope.ACCESS, ACLType.READ);
      OzoneObj volumeObj = OzoneObjInfo.Builder.newBuilder()
          .setVolumeName(volumeName).setStoreType(OzoneObj.StoreType.OZONE)
          .setResType(OzoneObj.ResourceType.VOLUME).build();
      OzoneObj bucketObj = OzoneObjInfo.Builder.newBuilder()
          .setVolumeName(volumeName).setBucketName(bucketName)
          .setStoreType(OzoneObj.StoreType.OZONE)
          .setResType(OzoneObj.ResourceType.BUCKET).build();
      store.addAcl(volumeObj, acl5);
      store.addAcl(volumeObj, acl6);
      store.addAcl(bucketObj, acl5);
      store.addAcl(bucketObj, acl6);

      // User without permission cannot start multi-upload
      String keyName2 = UUID.randomUUID().toString();
      OzoneBucket bucket2 = client.getObjectStore().getVolume(volumeName)
          .getBucket(bucketName);
      OMException ome = assertThrows(OMException.class,
          () -> initiateMultipartUpload(bucket2, keyName2, anyReplication()),
          "User without permission should fail");
      assertEquals(ResultCodes.PERMISSION_DENIED, ome.getResult());

      // Add create permission for user, and try multi-upload init again
      OzoneAcl acl7 = new OzoneAcl(ACLIdentityType.USER, userName, AclScope.DEFAULT, ACLType.CREATE);
      OzoneAcl acl8 = new OzoneAcl(ACLIdentityType.USER, userName, AclScope.ACCESS, ACLType.CREATE);
      OzoneAcl acl9 = new OzoneAcl(ACLIdentityType.USER, userName, AclScope.DEFAULT, ACLType.WRITE);
      OzoneAcl acl10 = new OzoneAcl(ACLIdentityType.USER, userName, AclScope.ACCESS, ACLType.WRITE);
      store.addAcl(volumeObj, acl7);
      store.addAcl(volumeObj, acl8);
      store.addAcl(volumeObj, acl9);
      store.addAcl(volumeObj, acl10);

      store.addAcl(bucketObj, acl7);
      store.addAcl(bucketObj, acl8);
      store.addAcl(bucketObj, acl9);
      store.addAcl(bucketObj, acl10);
      String uploadId = initiateMultipartUpload(bucket2, keyName2,
          anyReplication());

      // Upload part
      byte[] data = generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 1);
      Pair<String, String> partNameAndETag = uploadPart(bucket, keyName2,
          uploadId, 1, data);
      Map<Integer, String> eTagsMaps = new TreeMap<>();
      eTagsMaps.put(1, partNameAndETag.getValue());

      // Complete multipart upload request
      completeMultipartUpload(bucket2, keyName2, uploadId, eTagsMaps);

      // User without permission cannot read multi-uploaded object
      OMException ex = assertThrows(OMException.class, () -> {
        try (OzoneInputStream ignored = bucket2.readKey(keyName)) {
          LOG.error("User without permission should fail");
        }
      }, "User without permission should fail");
      assertEquals(ResultCodes.PERMISSION_DENIED, ex.getResult());
    }
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testMultipartUploadOverride(ReplicationConfig replication)
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    doMultipartUpload(bucket, keyName, (byte) 96, replication);

    // Initiate Multipart upload again, now we should read latest version, as
    // read always reads latest blocks.
    doMultipartUpload(bucket, keyName, (byte) 97, replication);

  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testUploadPartOverrideWithRatis(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    String sampleData = "sample Value";
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.THREE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    int partNumber = 1;
    Pair<String, String> partNameAndETag = uploadPart(bucket, keyName, uploadID,
        partNumber, sampleData.getBytes(UTF_8));

    // Overwrite the part by creating part key with same part number.
    Pair<String, String> partNameAndETagNew = uploadPart(bucket, keyName,
        uploadID, partNumber, "name".getBytes(UTF_8));

    // PartName should be same from old part Name.
    // AWS S3 for same content generates same partName during upload part.
    // In AWS S3 ETag is generated from md5sum. In Ozone right now we
    // don't do this. For now to make things work for large file upload
    // through aws s3 cp, the partName are generated in a predictable fashion.
    // So, when a part is override partNames will still be same irrespective
    // of content in ozone s3. This will make S3 Mpu completeMPU pass when
    // comparing part names and large file uploads work using aws cp.
    assertEquals(partNameAndETag.getKey(), partNameAndETagNew.getKey());

    // ETags are not equal due to content differences
    assertNotEquals(partNameAndETag.getValue(), partNameAndETagNew.getValue());

    // old part bytes written needs discard and have only
    // new part bytes in quota for this bucket
    long byteWritten = "name".length() * 3; // data written with replication
    assertEquals(volume.getBucket(bucketName).getUsedBytes(),
        byteWritten);
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testUploadTwiceWithEC(BucketLayout bucketLayout)
      throws IOException, NoSuchAlgorithmException {
    bucketName = UUID.randomUUID().toString();
    OzoneBucket bucket = getOzoneECBucket(bucketName, bucketLayout);

    byte[] data = generateData(81920, (byte) 97);
    // perform upload and complete
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName);

    String uploadID = multipartInfo.getUploadID();
    int partNumber = 1;

    Pair<String, String> partNameAndETag = uploadPart(bucket, keyName, uploadID,
        partNumber, data);

    Map<Integer, String> eTagsMap = new HashMap<>();
    eTagsMap.put(partNumber, partNameAndETag.getValue());
    bucket.completeMultipartUpload(keyName, uploadID, eTagsMap);

    long replicatedSize = QuotaUtil.getReplicatedSize(data.length,
        bucket.getReplicationConfig());
    assertEquals(volume.getBucket(bucketName).getUsedBytes(),
        replicatedSize);

    // upload same key again
    multipartInfo = bucket.initiateMultipartUpload(keyName);
    uploadID = multipartInfo.getUploadID();

    partNameAndETag = uploadPart(bucket, keyName, uploadID, partNumber,
        data);

    eTagsMap = new HashMap<>();
    eTagsMap.put(partNumber, partNameAndETag.getValue());
    bucket.completeMultipartUpload(keyName, uploadID, eTagsMap);

    // used sized should remain same, overwrite previous upload
    assertEquals(volume.getBucket(bucketName).getUsedBytes(),
        replicatedSize);
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testUploadAbortWithEC(BucketLayout bucketLayout)
      throws IOException, NoSuchAlgorithmException {
    byte[] data = generateData(81920, (byte) 97);

    bucketName = UUID.randomUUID().toString();
    OzoneBucket bucket = getOzoneECBucket(bucketName, bucketLayout);

    // perform upload and abort
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName);

    String uploadID = multipartInfo.getUploadID();
    int partNumber = 1;
    uploadPart(bucket, keyName, uploadID, partNumber, data);

    long replicatedSize = QuotaUtil.getReplicatedSize(data.length,
        bucket.getReplicationConfig());
    assertEquals(volume.getBucket(bucketName).getUsedBytes(),
        replicatedSize);

    bucket.abortMultipartUpload(keyName, uploadID);

    // used size should become zero after aport upload
    assertEquals(volume.getBucket(bucketName).getUsedBytes(), 0);
  }

  private OzoneBucket getOzoneECBucket(String myBucket, BucketLayout bucketLayout)
      throws IOException {
    final BucketArgs.Builder bucketArgs = BucketArgs.newBuilder();
    bucketArgs.setDefaultReplicationConfig(
        new DefaultReplicationConfig(
            new ECReplicationConfig(3, 2, ECReplicationConfig.EcCodec.RS,
                (int) OzoneConsts.MB)))
        .setBucketLayout(bucketLayout);

    volume.createBucket(myBucket, bucketArgs.build());
    return volume.getBucket(myBucket);
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMultipartUploadWithPartsLessThanMinSize(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    // Initiate multipart upload
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    // Upload Parts
    Map<Integer, String> eTagsMap = new TreeMap<>();
    // Uploading part 1 with less than min size
    Pair<String, String> partNameAndETag = uploadPart(bucket, keyName, uploadID,
        1, "data".getBytes(UTF_8));
    eTagsMap.put(1, partNameAndETag.getValue());

    partNameAndETag = uploadPart(bucket, keyName, uploadID, 2,
        "data".getBytes(UTF_8));
    eTagsMap.put(2, partNameAndETag.getValue());

    // Complete multipart upload
    OzoneTestUtils.expectOmException(OMException.ResultCodes.ENTITY_TOO_SMALL,
        () -> completeMultipartUpload(bucket, keyName, uploadID, eTagsMap));
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMultipartUploadWithDiscardedUnusedPartSize(BucketLayout bucketLayout)
      throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    // Initiate multipart upload
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    byte[] data = generateData(10000000, (byte) 97);

    // Upload Parts
    Map<Integer, String> eTagsMap = new TreeMap<>();

    // Upload part 1 and add it to the eTagsMap for completing the upload.
    Pair<String, String> partNameAndETag1 = uploadPart(bucket, keyName,
        uploadID, 1, data);
    eTagsMap.put(1, partNameAndETag1.getValue());

    // Upload part 2 and add it to the eTagsMap for completing the upload.
    Pair<String, String> partNameAndETag2 = uploadPart(bucket, keyName,
        uploadID, 2, data);
    eTagsMap.put(2, partNameAndETag2.getValue());

    // Upload part 3 but do not add it to the eTagsMap.
    uploadPart(bucket, keyName, uploadID, 3, data);

    completeMultipartUpload(bucket, keyName, uploadID, eTagsMap);

    // Check the bucket size. Since part number 3 was not added to the eTagsMap,
    // the unused part size should be discarded from the bucket size,
    // 30000000 - 10000000 = 20000000
    long bucketSize = volume.getBucket(bucketName).getUsedBytes();
    assertEquals(bucketSize, data.length * 2);
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMultipartUploadWithPartsMisMatchWithListSizeDifferent(BucketLayout bucketLayout)
      throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    // We have not uploaded any parts, but passing some list it should throw
    // error.
    TreeMap<Integer, String> partsMap = new TreeMap<>();
    partsMap.put(1, UUID.randomUUID().toString());

    OzoneTestUtils.expectOmException(OMException.ResultCodes.INVALID_PART,
        () -> completeMultipartUpload(bucket, keyName, uploadID, partsMap));
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMultipartUploadWithPartsMisMatchWithIncorrectPartName(BucketLayout bucketLayout)
      throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    uploadPart(bucket, keyName, uploadID, 1, "data".getBytes(UTF_8));

    // passing with an incorrect part name, should throw INVALID_PART error.
    TreeMap<Integer, String> partsMap = new TreeMap<>();
    partsMap.put(1, UUID.randomUUID().toString());

    OzoneTestUtils.expectOmException(OMException.ResultCodes.INVALID_PART,
        () -> completeMultipartUpload(bucket, keyName, uploadID, partsMap));
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMultipartUploadWithMissingParts(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    uploadPart(bucket, keyName, uploadID, 1, "data".getBytes(UTF_8));

    // passing with an incorrect part number, should throw INVALID_PART error.
    TreeMap<Integer, String> partsMap = new TreeMap<>();
    partsMap.put(3, "random");

    OzoneTestUtils.expectOmException(OMException.ResultCodes.INVALID_PART,
        () -> completeMultipartUpload(bucket, keyName, uploadID, partsMap));
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMultipartPartNumberExceedingAllowedRange(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    byte[] data = "data".getBytes(UTF_8);

    // Multipart part number must be an integer between 1 and 10000. So the
    // part number 1, 5000, 10000 will succeed,
    // the part number 0, 10001 will fail.
    bucket.createMultipartKey(keyName, data.length, 1, uploadID);
    bucket.createMultipartKey(keyName, data.length, 5000, uploadID);
    bucket.createMultipartKey(keyName, data.length, 10000, uploadID);
    OzoneTestUtils.expectOmException(OMException.ResultCodes.INVALID_PART,
        () -> bucket.createMultipartKey(
            keyName, data.length, 0, uploadID));
    OzoneTestUtils.expectOmException(OMException.ResultCodes.INVALID_PART,
        () -> bucket.createMultipartKey(
            keyName, data.length, 10001, uploadID));
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testCommitPartAfterCompleteUpload(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    String parentDir = "a/b/c/d/";
    keyName = parentDir + UUID.randomUUID();
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    if (bucketLayout.isFileSystemOptimized()) {
      assertEquals(volume.getBucket(bucketName).getUsedNamespace(), 4);
    }

    // upload part 1.
    byte[] data = generateData(5 * 1024 * 1024,
        (byte) RandomUtils.nextLong());
    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        data.length, 1, uploadID);
    ozoneOutputStream.write(data, 0, data.length);
    ozoneOutputStream.getMetadata().put(OzoneConsts.ETAG,
        DatatypeConverter.printHexBinary(eTagProvider.digest(data))
            .toLowerCase());
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo omMultipartCommitUploadPartInfo = ozoneOutputStream.getCommitUploadPartInfo();

    // Do not close output stream for part 2.
    ozoneOutputStream = bucket.createMultipartKey(keyName,
        data.length, 2, uploadID);
    ozoneOutputStream.getMetadata().put(OzoneConsts.ETAG,
        DatatypeConverter.printHexBinary(eTagProvider.digest(data))
            .toLowerCase());
    ozoneOutputStream.write(data, 0, data.length);

    Map<Integer, String> partsMap = new LinkedHashMap<>();
    partsMap.put(1, omMultipartCommitUploadPartInfo.getETag());
    OmMultipartUploadCompleteInfo omMultipartUploadCompleteInfo = bucket.completeMultipartUpload(keyName,
        uploadID, partsMap);
    assertNotNull(omMultipartUploadCompleteInfo);

    assertNotNull(omMultipartCommitUploadPartInfo);

    byte[] fileContent = new byte[data.length];
    try (OzoneInputStream inputStream = bucket.readKey(keyName)) {
      inputStream.read(fileContent);
    }
    StringBuilder sb = new StringBuilder(data.length);

    // Combine all parts data, and check is it matching with get key data.
    String part1 = new String(data, UTF_8);
    sb.append(part1);
    assertEquals(sb.toString(), new String(fileContent, UTF_8));
    OzoneOutputStream finalOzoneOutputStream = ozoneOutputStream;
    OMException ex = assertThrows(OMException.class, () -> finalOzoneOutputStream.close());
    assertEquals(NO_SUCH_MULTIPART_UPLOAD_ERROR, ex.getResult());
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testAbortUploadFail(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    OzoneTestUtils.expectOmException(NO_SUCH_MULTIPART_UPLOAD_ERROR,
        () -> bucket.abortMultipartUpload(keyName, "random"));
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testAbortUploadFailWithInProgressPartUpload(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    String parentDir = "a/b/c/d/";
    keyName = parentDir + UUID.randomUUID();

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    // Do not close output stream.
    byte[] data = "data".getBytes(UTF_8);
    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        data.length, 1, uploadID);
    ozoneOutputStream.write(data, 0, data.length);

    // Abort before completing part upload.
    bucket.abortMultipartUpload(keyName, uploadID);
    OMException ome = assertThrows(OMException.class, () -> ozoneOutputStream.close());
    assertEquals(NO_SUCH_MULTIPART_UPLOAD_ERROR, ome.getResult());
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testAbortUploadSuccessWithOutAnyParts(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    String parentDir = "a/b/c/d/";
    keyName = parentDir + UUID.randomUUID();

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    bucket.abortMultipartUpload(keyName, uploadID);
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testAbortUploadSuccessWithParts(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    String parentDir = "a/b/c/d/";
    keyName = parentDir + UUID.randomUUID();

    OzoneManager ozoneManager = cluster.getOzoneManager();
    String buckKey = ozoneManager.getMetadataManager()
        .getBucketKey(volume.getName(), bucket.getName());
    OmBucketInfo buckInfo = ozoneManager.getMetadataManager().getBucketTable().get(buckKey);
    BucketLayout bucketLayoutFromDB = buckInfo.getBucketLayout();

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    Pair<String, String> partNameAndETag = uploadPart(bucket, keyName, uploadID,
        1, "data".getBytes(UTF_8));

    OMMetadataManager metadataMgr = cluster.getOzoneManager().getMetadataManager();
    String multipartKey = verifyUploadedPart(uploadID, partNameAndETag.getKey(),
        metadataMgr);

    bucket.abortMultipartUpload(keyName, uploadID);

    String multipartOpenKey;
    if (bucketLayout.isFileSystemOptimized()) {
      multipartOpenKey = metadataMgr.getMultipartKeyFSO(volumeName, bucketName, keyName, uploadID);
    } else {
      multipartOpenKey = metadataMgr.getMultipartKey(volumeName, bucketName, keyName, uploadID);
    }
    OmKeyInfo omKeyInfo = metadataMgr.getOpenKeyTable(bucketLayoutFromDB).get(multipartOpenKey);
    OmMultipartKeyInfo omMultipartKeyInfo = metadataMgr.getMultipartInfoTable().get(multipartKey);
    assertNull(omKeyInfo);
    assertNull(omMultipartKeyInfo);

    // Since deleteTable operation is performed via
    // batchOp - Table.putWithBatch(), which is an async operation and
    // not making any assertion for the same.
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testListMultipartUploadParts(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    String parentDir = "a/b/c/d/e/f/";
    keyName = parentDir + "file-ABC";

    Map<Integer, String> partsMap = new TreeMap<>();
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    Pair<String, String> partNameAndETag1 = uploadPart(bucket, keyName,
        uploadID, 1, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(1, partNameAndETag1.getKey());

    Pair<String, String> partNameAndETag2 = uploadPart(bucket, keyName,
        uploadID, 2, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(2, partNameAndETag2.getKey());

    Pair<String, String> partNameAndETag3 = uploadPart(bucket, keyName,
        uploadID, 3, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(3, partNameAndETag3.getKey());

    OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts = bucket.listParts(keyName, uploadID, 0, 3);

    assertEquals(
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE),
        ozoneMultipartUploadPartListParts.getReplicationConfig());

    assertEquals(3,
        ozoneMultipartUploadPartListParts.getPartInfoList().size());

    verifyPartNamesInDB(partsMap,
        ozoneMultipartUploadPartListParts, uploadID);

    assertFalse(ozoneMultipartUploadPartListParts.isTruncated());
  }

  private void verifyPartNamesInDB(Map<Integer, String> partsMap,
      OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts,
      String uploadID) throws IOException {

    List<String> listPartNames = new ArrayList<>();
    String keyPartName = verifyPartNames(partsMap, 0,
        ozoneMultipartUploadPartListParts);
    listPartNames.add(keyPartName);

    keyPartName = verifyPartNames(partsMap, 1,
        ozoneMultipartUploadPartListParts);
    listPartNames.add(keyPartName);

    keyPartName = verifyPartNames(partsMap, 2,
        ozoneMultipartUploadPartListParts);
    listPartNames.add(keyPartName);

    OMMetadataManager metadataMgr = cluster.getOzoneManager().getMetadataManager();
    String multipartKey = metadataMgr.getMultipartKey(volumeName, bucketName,
        keyName, uploadID);
    OmMultipartKeyInfo omMultipartKeyInfo = metadataMgr.getMultipartInfoTable().get(multipartKey);
    assertNotNull(omMultipartKeyInfo);

    for (OzoneManagerProtocolProtos.PartKeyInfo partKeyInfo : omMultipartKeyInfo.getPartKeyInfoMap()) {
      String partKeyName = partKeyInfo.getPartName();

      // reconstruct full part name with volume, bucket, partKeyName
      String fullKeyPartName = metadataMgr.getOzoneKey(volumeName, bucketName, keyName);

      // partKeyName format in DB - partKeyName + ClientID
      assertTrue(partKeyName.startsWith(fullKeyPartName),
          "Invalid partKeyName format in DB: " + partKeyName
              + ", expected name:" + fullKeyPartName);

      listPartNames.remove(partKeyName);
    }
    assertThat(listPartNames).withFailMessage("Wrong partKeyName format in DB!").isEmpty();
  }

  private String verifyPartNames(Map<Integer, String> partsMap, int index,
      OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts) {

    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
        .getPartInfoList().get(index).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(index)
            .getPartName());

    return ozoneMultipartUploadPartListParts.getPartInfoList().get(index)
        .getPartName();
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testListMultipartUploadPartsWithContinuation(BucketLayout bucketLayout)
      throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    Map<Integer, String> partsMap = new TreeMap<>();
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    Pair<String, String> partNameAndETag1 = uploadPart(bucket, keyName,
        uploadID, 1, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(1, partNameAndETag1.getKey());

    Pair<String, String> partNameAndETag2 = uploadPart(bucket, keyName,
        uploadID, 2, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(2, partNameAndETag2.getKey());

    Pair<String, String> partNameAndETag3 = uploadPart(bucket, keyName,
        uploadID, 3, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(3, partNameAndETag3.getKey());

    OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts = bucket.listParts(keyName, uploadID, 0, 2);

    assertEquals(
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE),
        ozoneMultipartUploadPartListParts.getReplicationConfig());

    assertEquals(2,
        ozoneMultipartUploadPartListParts.getPartInfoList().size());

    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
        .getPartInfoList().get(0).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(0)
            .getPartName());
    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
        .getPartInfoList().get(1).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(1)
            .getPartName());

    // Get remaining
    assertTrue(ozoneMultipartUploadPartListParts.isTruncated());
    ozoneMultipartUploadPartListParts = bucket.listParts(keyName, uploadID,
        ozoneMultipartUploadPartListParts.getNextPartNumberMarker(), 2);

    assertEquals(1,
        ozoneMultipartUploadPartListParts.getPartInfoList().size());
    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
        .getPartInfoList().get(0).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(0)
            .getPartName());

    // As we don't have any parts for this, we should get false here
    assertFalse(ozoneMultipartUploadPartListParts.isTruncated());

  }

  static Stream<Arguments> listPartsInvalidInputs() {
    return Stream.of(
        BucketLayout.OBJECT_STORE,
        BucketLayout.LEGACY,
        BucketLayout.FILE_SYSTEM_OPTIMIZED).flatMap(
            layout -> Stream.of(
                Arguments.of(layout, -1, 2, "Should be greater than or equal to zero"),
                Arguments.of(layout, 1, -1, "Max Parts Should be greater than zero")));
  }

  @ParameterizedTest
  @MethodSource("listPartsInvalidInputs")
  public void testListPartsWithInvalidInputs(
      BucketLayout bucketLayout,
      int partNumberMarker,
      int maxParts,
      String expectedErrorMessage) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> bucket.listParts(keyName, "random", partNumberMarker, maxParts));

    assertThat(exception).hasMessageContaining(expectedErrorMessage);
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testListPartsWithPartMarkerGreaterThanPartCount(BucketLayout bucketLayout)
      throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    uploadPart(bucket, keyName, uploadID, 1,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));

    OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts = bucket.listParts(keyName, uploadID, 100, 2);

    // Should return empty
    assertEquals(0, ozoneMultipartUploadPartListParts.getPartInfoList().size());

    assertEquals(
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE),
        ozoneMultipartUploadPartListParts.getReplicationConfig());

    // As we don't have any parts with greater than partNumberMarker and list
    // is not truncated, so it should return false here.
    assertFalse(ozoneMultipartUploadPartListParts.isTruncated());

  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testListPartsWithInvalidUploadID(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    OzoneTestUtils
        .expectOmException(NO_SUCH_MULTIPART_UPLOAD_ERROR, () -> {
          OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts = bucket.listParts(keyName, "random", 100,
              2);
        });
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testListMultipartUpload(BucketLayout bucketLayout) throws Exception {
    volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket bucket = volume.getBucket(bucketName);

    String dirName = "dir1/dir2/dir3";
    String key1 = "dir1" + "/key1";
    String key2 = "dir1/dir2" + "/key2";
    String key3 = dirName + "/key3";
    List<String> keys = new ArrayList<>();
    keys.add(key1);
    keys.add(key2);
    keys.add(key3);

    // Initiate multipart upload
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID1 = initiateMultipartUpload(bucket, key1, replication);
    String uploadID2 = initiateMultipartUpload(bucket, key2, replication);
    String uploadID3 = initiateMultipartUpload(bucket, key3, replication);

    // Upload Parts
    // Uploading part 1 with less than min size
    uploadPart(bucket, key1, uploadID1, 1, "data".getBytes(UTF_8));
    uploadPart(bucket, key2, uploadID2, 1, "data".getBytes(UTF_8));
    uploadPart(bucket, key3, uploadID3, 1, "data".getBytes(UTF_8));

    OzoneMultipartUploadList listMPUs = bucket.listMultipartUploads("dir1");
    assertEquals(3, listMPUs.getUploads().size());
    List<String> expectedList = new ArrayList<>(keys);
    for (OzoneMultipartUpload mpu : listMPUs.getUploads()) {
      expectedList.remove(mpu.getKeyName());
    }
    assertEquals(0, expectedList.size());

    listMPUs = bucket.listMultipartUploads("dir1/dir2");
    assertEquals(2, listMPUs.getUploads().size());
    expectedList = new ArrayList<>();
    expectedList.add(key2);
    expectedList.add(key3);
    for (OzoneMultipartUpload mpu : listMPUs.getUploads()) {
      expectedList.remove(mpu.getKeyName());
    }
    assertEquals(0, expectedList.size());

    listMPUs = bucket.listMultipartUploads("dir1/dir2/dir3");
    assertEquals(1, listMPUs.getUploads().size());
    expectedList = new ArrayList<>();
    expectedList.add(key3);
    for (OzoneMultipartUpload mpu : listMPUs.getUploads()) {
      expectedList.remove(mpu.getKeyName());
    }
    assertEquals(0, expectedList.size());

    // partial key
    listMPUs = bucket.listMultipartUploads("d");
    assertEquals(3, listMPUs.getUploads().size());
    expectedList = new ArrayList<>(keys);
    for (OzoneMultipartUpload mpu : listMPUs.getUploads()) {
      expectedList.remove(mpu.getKeyName());
    }
    assertEquals(0, expectedList.size());

    // partial key
    listMPUs = bucket.listMultipartUploads("");
    assertEquals(3, listMPUs.getUploads().size());
    expectedList = new ArrayList<>(keys);
    for (OzoneMultipartUpload mpu : listMPUs.getUploads()) {
      expectedList.remove(mpu.getKeyName());
    }
    assertEquals(0, expectedList.size());
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  void testGetAllPartsWhenZeroPartNumber(BucketLayout bucketLayout) throws Exception {
    String parentDir = "a/b/c/d/e/f/";
    keyName = parentDir + "file-ABC";
    OzoneVolume s3volume = store.getVolume("s3v");
    s3volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket s3Bucket = s3volume.getBucket(bucketName);

    Map<Integer, String> partsMap = new TreeMap<>();
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(s3Bucket, keyName, replication);

    Pair<String, String> partNameAndETag1 = uploadPart(s3Bucket, keyName,
        uploadID, 1, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(1, partNameAndETag1.getKey());

    Pair<String, String> partNameAndETag2 = uploadPart(s3Bucket, keyName,
        uploadID, 2, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(2, partNameAndETag2.getKey());

    Pair<String, String> partNameAndETag3 = uploadPart(s3Bucket, keyName,
        uploadID, 3, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(3, partNameAndETag3.getKey());

    s3Bucket.completeMultipartUpload(keyName, uploadID, partsMap);

    OzoneKeyDetails s3KeyDetailsWithAllParts = ozClient.getProxy()
        .getS3KeyDetails(s3Bucket.getName(), keyName, 0);
    List<OzoneKeyLocation> ozoneKeyLocations = s3KeyDetailsWithAllParts.getOzoneKeyLocations();
    assertEquals(6, ozoneKeyLocations.size());
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  void testGetParticularPart(BucketLayout bucketLayout) throws Exception {
    String parentDir = "a/b/c/d/e/f/";
    keyName = parentDir + "file-ABC";
    OzoneVolume s3volume = store.getVolume("s3v");
    s3volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket s3Bucket = s3volume.getBucket(bucketName);

    Map<Integer, String> partsMap = new TreeMap<>();
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(s3Bucket, keyName, replication);

    Pair<String, String> partNameAndETag1 = uploadPart(s3Bucket, keyName,
        uploadID, 1, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(1, partNameAndETag1.getKey());

    Pair<String, String> partNameAndETag2 = uploadPart(s3Bucket, keyName,
        uploadID, 2, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(2, partNameAndETag2.getKey());

    Pair<String, String> partNameAndETag3 = uploadPart(s3Bucket, keyName,
        uploadID, 3, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(3, partNameAndETag3.getKey());

    s3Bucket.completeMultipartUpload(keyName, uploadID, partsMap);

    // OzoneKeyLocations size is 2 because part size is 5MB and ozone.scm.block.size
    // in ozone-site.xml
    // for integration-test is 4MB
    OzoneKeyDetails s3KeyDetailsOneParts = ozClient.getProxy().getS3KeyDetails(bucketName, keyName, 1);
    assertEquals(2, s3KeyDetailsOneParts.getOzoneKeyLocations().size());

    OzoneKeyDetails s3KeyDetailsTwoParts = ozClient.getProxy().getS3KeyDetails(bucketName, keyName, 2);
    assertEquals(2, s3KeyDetailsTwoParts.getOzoneKeyLocations().size());

    OzoneKeyDetails s3KeyDetailsThreeParts = ozClient.getProxy().getS3KeyDetails(bucketName, keyName, 3);
    assertEquals(2, s3KeyDetailsThreeParts.getOzoneKeyLocations().size());
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  void testGetNotExistedPart(BucketLayout bucketLayout) throws Exception {
    String parentDir = "a/b/c/d/e/f/";
    keyName = parentDir + "file-ABC";
    OzoneVolume s3volume = store.getVolume("s3v");
    s3volume.createBucket(bucketName, getBucketArgs(bucketLayout));
    OzoneBucket s3Bucket = s3volume.getBucket(bucketName);

    Map<Integer, String> partsMap = new TreeMap<>();
    ReplicationConfig replication = RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(s3Bucket, keyName, replication);

    Pair<String, String> partNameAndETag1 = uploadPart(s3Bucket, keyName,
        uploadID, 1, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(1, partNameAndETag1.getKey());

    Pair<String, String> partNameAndETag2 = uploadPart(s3Bucket, keyName,
        uploadID, 2, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(2, partNameAndETag2.getKey());

    Pair<String, String> partNameAndETag3 = uploadPart(s3Bucket, keyName,
        uploadID, 3, generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 97));
    partsMap.put(3, partNameAndETag3.getKey());

    s3Bucket.completeMultipartUpload(keyName, uploadID, partsMap);

    OzoneKeyDetails s3KeyDetailsWithNotExistedParts = ozClient.getProxy()
        .getS3KeyDetails(s3Bucket.getName(), keyName, 4);
    List<OzoneKeyLocation> ozoneKeyLocations = s3KeyDetailsWithNotExistedParts.getOzoneKeyLocations();
    assertEquals(0, ozoneKeyLocations.size());
  }

  protected void verifyReplication(String volumeName, String bucketName,
      String keyName, ReplicationConfig replication)
      throws IOException {
    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .build();
    OmKeyInfo keyInfo = ozoneManager.lookupKey(keyArgs);
    for (OmKeyLocationInfo info : keyInfo.getLatestVersionLocations().getLocationList()) {
      ContainerInfo container = storageContainerLocationClient.getContainer(info.getContainerID());
      assertEquals(replication, container.getReplicationConfig());
    }
  }

  private void doMultipartUpload(OzoneBucket bucket, String keyName, byte val,
      ReplicationConfig replication)
      throws Exception {
    doMultipartUpload(bucket, keyName, val, replication, Collections.emptyMap(), Collections.emptyMap());
  }

  private void doMultipartUpload(OzoneBucket bucket, String keyName, byte val,
      ReplicationConfig replication, Map<String, String> customMetadata, Map<String, String> tags)
      throws Exception {
    // Initiate Multipart upload request
    String uploadID = initiateMultipartUpload(bucket, keyName, replication, customMetadata, tags);

    // Upload parts
    Map<Integer, String> partsMap = new TreeMap<>();

    // get 5mb data, as each part should be of min 5mb, last part can be less
    // than 5mb
    int length = 0;
    byte[] data = generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, val);
    Pair<String, String> partNameAndEtag = uploadPart(bucket, keyName, uploadID,
        1, data);
    partsMap.put(1, partNameAndEtag.getValue());
    length += data.length;

    partNameAndEtag = uploadPart(bucket, keyName, uploadID, 2, data);
    partsMap.put(2, partNameAndEtag.getValue());
    length += data.length;

    String part3 = UUID.randomUUID().toString();
    partNameAndEtag = uploadPart(bucket, keyName, uploadID, 3, part3.getBytes(
        UTF_8));
    partsMap.put(3, partNameAndEtag.getValue());
    length += part3.getBytes(UTF_8).length;

    // Complete multipart upload request
    completeMultipartUpload(bucket, keyName, uploadID, partsMap);

    // Now Read the key which has been completed multipart upload.
    byte[] fileContent = new byte[data.length + data.length + part3.getBytes(
        UTF_8).length];
    try (OzoneInputStream inputStream = bucket.readKey(keyName)) {
      inputStream.read(fileContent);
    }

    verifyReplication(bucket.getVolumeName(), bucket.getName(), keyName,
        replication);

    StringBuilder sb = new StringBuilder(length);

    // Combine all parts data, and check is it matching with get key data.
    String part1 = new String(data, UTF_8);
    String part2 = new String(data, UTF_8);
    sb.append(part1);
    sb.append(part2);
    sb.append(part3);
    assertEquals(sb.toString(), new String(fileContent, UTF_8));

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(bucket.getVolumeName())
        .setBucketName(bucket.getName())
        .setKeyName(keyName)
        .build();
    ResolvedBucket resolvedBucket = new ResolvedBucket(
        bucket.getVolumeName(), bucket.getName(),
        bucket.getVolumeName(), bucket.getName(),
        "", bucket.getBucketLayout());
    OmKeyInfo omKeyInfo = ozoneManager.getKeyManager().getKeyInfo(keyArgs,
        resolvedBucket, UUID.randomUUID().toString());

    OmKeyLocationInfoGroup latestVersionLocations = omKeyInfo.getLatestVersionLocations();
    assertNotNull(latestVersionLocations);
    assertTrue(latestVersionLocations.isMultipartKey());
    latestVersionLocations.getBlocksLatestVersionOnly()
        .forEach(omKeyLocationInfo -> assertNotEquals(-1, omKeyLocationInfo.getPartNumber()));

    Map<String, String> keyMetadata = omKeyInfo.getMetadata();
    assertNotNull(keyMetadata.get(ETAG));
    if (customMetadata != null && !customMetadata.isEmpty()) {
      assertThat(keyMetadata).containsAllEntriesOf(customMetadata);
    }

    Map<String, String> keyTags = omKeyInfo.getTags();
    if (keyTags != null && !keyTags.isEmpty()) {
      assertThat(keyTags).containsAllEntriesOf(tags);
    }
  }

  private String verifyUploadedPart(String uploadID, String partName,
      OMMetadataManager metadataMgr) throws IOException {
    OzoneManager ozoneManager = cluster.getOzoneManager();
    String buckKey = ozoneManager.getMetadataManager()
        .getBucketKey(volumeName, bucketName);
    OmBucketInfo buckInfo = ozoneManager.getMetadataManager().getBucketTable().get(buckKey);
    BucketLayout bucketLayout = buckInfo.getBucketLayout();
    String multipartOpenKey;
    if (bucketLayout.isFileSystemOptimized()) {
      multipartOpenKey = metadataMgr.getMultipartKeyFSO(volumeName, bucketName, keyName, uploadID);
    } else {
      multipartOpenKey = metadataMgr.getMultipartKey(volumeName, bucketName, keyName, uploadID);
    }

    String multipartKey = metadataMgr.getMultipartKey(volumeName, bucketName,
        keyName, uploadID);
    OmKeyInfo omKeyInfo = metadataMgr.getOpenKeyTable(bucketLayout).get(multipartOpenKey);
    OmMultipartKeyInfo omMultipartKeyInfo = metadataMgr.getMultipartInfoTable().get(multipartKey);

    assertNotNull(omKeyInfo);
    assertNotNull(omMultipartKeyInfo);
    assertEquals(keyName, omKeyInfo.getKeyName());
    assertEquals(OzoneFSUtils.getFileName(keyName), omKeyInfo.getFileName());
    assertEquals(uploadID, omMultipartKeyInfo.getUploadID());

    for (OzoneManagerProtocolProtos.PartKeyInfo partKeyInfo : omMultipartKeyInfo.getPartKeyInfoMap()) {
      OmKeyInfo currentKeyPartInfo = OmKeyInfo.getFromProtobuf(partKeyInfo.getPartKeyInfo());

      assertEquals(keyName, currentKeyPartInfo.getKeyName());

      // verify dbPartName
      assertEquals(partName, partKeyInfo.getPartName());
    }
    return multipartKey;
  }

  private String initiateMultipartUpload(OzoneBucket bucket, String keyName,
      ReplicationConfig replicationConfig) throws Exception {
    return initiateMultipartUpload(bucket, keyName, replicationConfig, Collections.emptyMap(), Collections.emptyMap());
  }

  private String initiateMultipartUpload(OzoneBucket bucket, String keyName,
      ReplicationConfig replicationConfig, Map<String, String> customMetadata,
      Map<String, String> tags) throws Exception {
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replicationConfig, customMetadata, tags);

    String uploadID = multipartInfo.getUploadID();
    assertNotNull(uploadID);
    return uploadID;
  }

  private Pair<String, String> uploadPart(OzoneBucket oBucket, String kName,
      String uploadID, int partNumber,
      byte[] data)
      throws IOException, NoSuchAlgorithmException {

    OzoneOutputStream ozoneOutputStream = oBucket.createMultipartKey(kName,
        data.length, partNumber, uploadID);
    ozoneOutputStream.write(data, 0, data.length);
    ozoneOutputStream.getMetadata().put(OzoneConsts.ETAG,
        DatatypeConverter.printHexBinary(eTagProvider.digest(data))
            .toLowerCase());
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo omMultipartCommitUploadPartInfo = ozoneOutputStream.getCommitUploadPartInfo();

    assertNotNull(omMultipartCommitUploadPartInfo);
    assertNotNull(omMultipartCommitUploadPartInfo.getETag());

    assertNotNull(omMultipartCommitUploadPartInfo.getPartName());

    return Pair.of(omMultipartCommitUploadPartInfo.getPartName(),
        omMultipartCommitUploadPartInfo.getETag());
  }

  private void completeMultipartUpload(OzoneBucket bucket, String keyName,
      String uploadID, Map<Integer, String> partsMap) throws Exception {
    OmMultipartUploadCompleteInfo omMultipartUploadCompleteInfo = bucket
        .completeMultipartUpload(keyName, uploadID, partsMap);

    assertNotNull(omMultipartUploadCompleteInfo);
    assertEquals(omMultipartUploadCompleteInfo.getKey(), keyName);
    assertNotNull(omMultipartUploadCompleteInfo.getHash());
  }

  private byte[] generateData(int size, byte val) {
    byte[] chars = new byte[size];
    Arrays.fill(chars, val);
    return chars;
  }
}
