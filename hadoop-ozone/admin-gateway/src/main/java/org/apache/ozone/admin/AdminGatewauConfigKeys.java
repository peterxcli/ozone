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

import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.hdds.annotation.InterfaceStability;

/**
 * This class contains constants for configuration keys used in Admin Gateway.
 */
@InterfaceAudience.Public
@InterfaceStability.Unstable
public final class AdminGatewauConfigKeys {

  public static final String OZONE_ADMIN_GATEWAY_HTTP_ENABLED_KEY =
      "ozone.admin.gateway.http.enabled";
  public static final String OZONE_ADMIN_GATEWAY_HTTP_BIND_HOST_KEY =
      "ozone.admin.gateway.http-bind-host";
  public static final String OZONE_ADMIN_GATEWAY_HTTPS_BIND_HOST_KEY =
      "ozone.admin.gateway.https-bind-host";
  public static final String OZONE_ADMIN_GATEWAY_HTTP_ADDRESS_KEY =
      "ozone.admin.gateway.http-address";
  public static final String OZONE_ADMIN_GATEWAY_HTTPS_ADDRESS_KEY =
      "ozone.admin.gateway.https-address";
  public static final String OZONE_ADMIN_GATEWAY_HTTP_BIND_HOST_DEFAULT = "0.0.0.0";
  public static final int OZONE_ADMIN_GATEWAY_HTTP_BIND_PORT_DEFAULT = 9842;
  public static final int OZONE_ADMIN_GATEWAY_HTTPS_BIND_PORT_DEFAULT = 9841;
  public static final String OZONE_ADMIN_GATEWAY_AUTH_CONFIG_PREFIX =
      "ozone.admin.gateway.http.auth.";
  public static final String OZONE_ADMIN_GATEWAY_HTTP_AUTH_TYPE =
      OZONE_ADMIN_GATEWAY_AUTH_CONFIG_PREFIX + "type";
  public static final String OZONE_ADMIN_GATEWAY_KEYTAB_FILE =
      OZONE_ADMIN_GATEWAY_AUTH_CONFIG_PREFIX + "kerberos.keytab";
  public static final String OZONE_ADMIN_GATEWAY_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL =
      OZONE_ADMIN_GATEWAY_AUTH_CONFIG_PREFIX + "kerberos.principal";

  /**
   * Never constructed.
   */
  private AdminGatewauConfigKeys() {

  }
}
