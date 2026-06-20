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

import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hdds.utils.db.Codec;
import org.apache.hadoop.hdds.utils.db.DelegatedCodec;
import org.apache.hadoop.hdds.utils.db.Proto2Codec;
import org.apache.hadoop.ozone.ClientVersion;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyInfo;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.RepeatedKeyInfo;

/**
 * Codec for repeated open key values.
 */
public final class RepeatedOmOpenKeyInfoCodec {
  private static final Codec<RepeatedOmKeyInfo> CODEC_TRUE = newCodec(true);
  private static final Codec<RepeatedOmKeyInfo> CODEC_FALSE = newCodec(false);

  private RepeatedOmOpenKeyInfoCodec() {
  }

  public static Codec<RepeatedOmKeyInfo> get(boolean ignorePipeline) {
    return ignorePipeline ? CODEC_TRUE : CODEC_FALSE;
  }

  private static Codec<RepeatedOmKeyInfo> newCodec(boolean ignorePipeline) {
    return new DelegatedCodec<>(
        Proto2Codec.get(RepeatedKeyInfo.getDefaultInstance()),
        RepeatedOmKeyInfo::getFromProto,
        k -> getProto(k, ignorePipeline, ClientVersion.CURRENT_VERSION),
        RepeatedOmKeyInfo.class);
  }

  private static RepeatedKeyInfo getProto(RepeatedOmKeyInfo repeatedOmKeyInfo,
                                          boolean ignorePipeline,
                                          int clientVersion) {
    List<KeyInfo> list = new ArrayList<>();
    for (OmKeyInfo k : repeatedOmKeyInfo.cloneOmKeyInfoList()) {
      list.add(OmOpenKeyInfoCodec.getProtobuf(k, ignorePipeline, clientVersion));
    }
    return RepeatedKeyInfo.newBuilder()
        .addAllKeyInfo(list)
        .setBucketId(repeatedOmKeyInfo.getBucketId())
        .build();
  }
}
