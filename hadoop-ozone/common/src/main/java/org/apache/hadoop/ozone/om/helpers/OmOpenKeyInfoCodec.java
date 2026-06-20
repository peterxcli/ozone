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

package org.apache.hadoop.ozone.om.helpers;

import org.apache.hadoop.hdds.utils.db.Codec;
import org.apache.hadoop.hdds.utils.db.DelegatedCodec;
import org.apache.hadoop.hdds.utils.db.Proto2Codec;
import org.apache.hadoop.ozone.ClientVersion;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyInfo;

/**
 * Codec for open key table values.
 */
public final class OmOpenKeyInfoCodec {
  private static final Codec<OmKeyInfo> CODEC = new DelegatedCodec<>(
      Proto2Codec.get(KeyInfo.getDefaultInstance()),
      OmKeyInfo::getFromProtobuf,
      k -> getProtobuf(k, true, ClientVersion.CURRENT_VERSION),
      OmKeyInfo.class);

  private OmOpenKeyInfoCodec() {
  }

  public static Codec<OmKeyInfo> get() {
    return CODEC;
  }

  static KeyInfo getProtobuf(OmKeyInfo keyInfo, boolean ignorePipeline,
                             int clientVersion) {
    KeyInfo.Builder builder = keyInfo.getProtobufBuilder(ignorePipeline,
        clientVersion);
    if (keyInfo.getExpectedDataGeneration() != null) {
      builder.setExpectedDataGeneration(keyInfo.getExpectedDataGeneration());
    }
    return builder.build();
  }
}
