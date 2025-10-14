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

package org.apache.hadoop.ozone.security.acl;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example custom authorizer for testing and demonstration purposes.
 * This authorizer allows all users in a configured allowlist and denies everyone else.
 *
 * <p>Configuration:
 * <pre>
 * ozone.acl.authorizer.class=org.apache.hadoop.ozone.security.acl.ExampleCustomAuthorizer
 * ozone.authorizer.example.allowed.users=alice,bob,charlie
 * </pre>
 *
 * <p>This demonstrates:
 * - How to extend {@link BaseAuthorizerPlugin}
 * - Configuration loading in {@code doStart()}
 * - Simple authorization logic in {@code doCheckAccess()}
 * - Automatic caching and metrics (inherited from base class)
 */
public class ExampleCustomAuthorizer extends BaseAuthorizerPlugin {

  private static final Logger LOG = LoggerFactory.getLogger(
      ExampleCustomAuthorizer.class);

  private static final String ALLOWED_USERS_KEY =
      "ozone.authorizer.example.allowed.users";

  private Set<String> allowedUsers = new HashSet<>();

  @Override
  protected void doStart(ConfigurationSource conf) throws IOException {
    String allowedUsersStr = conf.get(ALLOWED_USERS_KEY, "");

    if (allowedUsersStr.isEmpty()) {
      LOG.warn("No allowed users configured. All requests will be denied!");
    } else {
      String[] users = allowedUsersStr.split(",");
      for (String user : users) {
        allowedUsers.add(user.trim());
      }
      LOG.info("Example authorizer started with allowed users: {}",
          allowedUsers);
    }
  }

  @Override
  protected boolean doCheckAccess(IOzoneObj ozoneObject,
      RequestContext context) throws OMException {

    String user = context.getClientUgi().getShortUserName();
    boolean allowed = allowedUsers.contains(user);

    LOG.debug("Authorization check: user={}, resource={}, action={}, allowed={}",
        user, buildResourcePath(ozoneObject), context.getAclRights(), allowed);

    return allowed;
  }

  @Override
  protected void doStop() throws IOException {
    allowedUsers.clear();
    LOG.info("Example authorizer stopped");
  }

  @Override
  public String getName() {
    return "ExampleCustomAuthorizer";
  }

  @Override
  public String getStatus() {
    return String.format("Allowed users: %d", allowedUsers.size());
  }
}
