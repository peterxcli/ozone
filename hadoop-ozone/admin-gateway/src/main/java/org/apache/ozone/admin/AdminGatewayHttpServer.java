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
import org.apache.hadoop.hdds.conf.MutableConfigurationSource;
import org.apache.hadoop.hdds.server.http.BaseHttpServer;

/**
 * Admin Gateway specific configuration keys.
 */
public class AdminGatewayHttpServer extends BaseHttpServer {

  public AdminGatewayHttpServer(MutableConfigurationSource conf, String name)
      throws IOException {
    super(conf, name);
  }

  @Override
  protected String getHttpAddressKey() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTP_ADDRESS_KEY;
  }

  @Override
  protected String getHttpsAddressKey() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTPS_ADDRESS_KEY;
  }

  @Override
  protected String getHttpBindHostKey() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTP_BIND_HOST_KEY;
  }

  @Override
  protected String getHttpsBindHostKey() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTPS_BIND_HOST_KEY;
  }

  @Override
  protected String getBindHostDefault() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTP_BIND_HOST_DEFAULT;
  }

  @Override
  protected int getHttpBindPortDefault() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTP_BIND_PORT_DEFAULT;
  }

  @Override
  protected int getHttpsBindPortDefault() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTPS_BIND_PORT_DEFAULT;
  }

  @Override
  protected String getKeytabFile() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_KEYTAB_FILE;
  }

  @Override
  protected String getSpnegoPrincipal() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL;
  }

  @Override
  protected String getEnabledKey() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTP_ENABLED_KEY;
  }

  @Override
  protected String getHttpAuthType() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_HTTP_AUTH_TYPE;
  }

  @Override
  protected String getHttpAuthConfigPrefix() {
    return AdminGatewauConfigKeys.OZONE_ADMIN_GATEWAY_AUTH_CONFIG_PREFIX;
  }
}
