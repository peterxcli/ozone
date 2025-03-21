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

package org.apache.hadoop.ozone.fsck;

import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_DB_DIRS;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.db.TableIterator;
import org.apache.hadoop.ozone.ClientVersion;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;

/**
 * Generates Container Id to Blocks and BlockDetails mapping.
 */
public class ContainerMapper {

  public static void main(String[] args) throws IOException {
    String path = args[0];
    if (path == null) {
      throw new IOException("Path cannot be null");
    }

    OzoneConfiguration configuration = new OzoneConfiguration();
    configuration.set(OZONE_OM_DB_DIRS, path);

    ContainerMapper containerMapper = new ContainerMapper();
    Map<Long, List<Map<Long, BlockIdDetails>>> dataMap =
        containerMapper.parseOmDB(configuration);


    ObjectMapper mapper = new ObjectMapper();
    System.out.println(mapper.writeValueAsString(dataMap));

  }

  /**
   * Generates Container Id to Blocks and BlockDetails mapping.
   * @param configuration @{@link OzoneConfiguration}
   * @return {@code Map<Long, List<Map<Long, BlockDetails>>>
   *   Map of ContainerId -> (Block, Block info)}
   * @throws IOException
   */
  public Map<Long, List<Map<Long, BlockIdDetails>>>
      parseOmDB(OzoneConfiguration configuration) throws IOException {
    String path = configuration.get(OZONE_OM_DB_DIRS);
    if (path == null || path.isEmpty()) {
      throw new IOException(OZONE_OM_DB_DIRS + "should be set ");
    }
    OmMetadataManagerImpl metadataManager =
        new OmMetadataManagerImpl(configuration, null);
    try {
      Table<String, OmKeyInfo> keyTable =
          metadataManager.getKeyTable(getBucketLayout());
      Map<Long, List<Map<Long, BlockIdDetails>>> dataMap = new HashMap<>();

      if (keyTable != null) {
        try (TableIterator<String, ? extends Table.KeyValue<String, OmKeyInfo>>
                 keyValueTableIterator = keyTable.iterator()) {
          while (keyValueTableIterator.hasNext()) {
            Table.KeyValue<String, OmKeyInfo> keyValue =
                keyValueTableIterator.next();
            OmKeyInfo omKeyInfo = keyValue.getValue();
            byte[] value = omKeyInfo
                .getProtobuf(true, ClientVersion.CURRENT_VERSION)
                .toByteArray();
            OmKeyInfo keyInfo = OmKeyInfo.getFromProtobuf(
                OzoneManagerProtocolProtos.KeyInfo.parseFrom(value));
            for (OmKeyLocationInfoGroup keyLocationInfoGroup : keyInfo
                .getKeyLocationVersions()) {
              List<OmKeyLocationInfo> keyLocationInfo = keyLocationInfoGroup
                  .getLocationList();
              for (OmKeyLocationInfo keyLocation : keyLocationInfo) {
                BlockIdDetails blockIdDetails = new BlockIdDetails();
                Map<Long, BlockIdDetails> innerMap = new HashMap<>();

                long containerID = keyLocation.getBlockID().getContainerID();
                long blockID = keyLocation.getBlockID().getLocalID();
                blockIdDetails.setBucketName(keyInfo.getBucketName());
                blockIdDetails.setBlockVol(keyInfo.getVolumeName());
                blockIdDetails.setKeyName(keyInfo.getKeyName());

                List<Map<Long, BlockIdDetails>> innerList = new ArrayList<>();
                innerMap.put(blockID, blockIdDetails);

                if (dataMap.containsKey(containerID)) {
                  innerList = dataMap.get(containerID);
                }

                innerList.add(innerMap);
                dataMap.put(containerID, innerList);
              }
            }
          }
        }
      }

      return dataMap;

    } finally {
      metadataManager.stop();
    }
  }

  private static BucketLayout getBucketLayout() {
    return BucketLayout.DEFAULT;
  }
}


