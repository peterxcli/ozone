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
import java.util.concurrent.Callable;
import org.apache.hadoop.hdds.cli.GenericCli;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.util.OzoneNetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

/**
 * This class is used to start/stop admin rest server.
 */
@Command(name = "ozone ag",
    hidden = true, description = "admin rest server.",
    versionProvider = HddsVersionProvider.class,
    mixinStandardHelpOptions = true)
public class Gateway extends GenericCli implements Callable<Void> {

  private static final Logger LOG = LoggerFactory.getLogger(Gateway.class);

  private AdminGatewayHttpServer httpServer;

  public static void main(String[] args) throws Exception {
    OzoneNetUtils.disableJvmNetworkAddressCacheIfRequired(
        new OzoneConfiguration());
    new Gateway().run(args);
  }

  @Override
  public Void call() throws Exception {
    OzoneConfiguration conf = getOzoneConf();
    OzoneConfigurationHolder.setConfiguration(conf);
    httpServer = new AdminGatewayHttpServer(conf, "adminGateway");
    start();
    return null;
  }

  public void start() throws IOException {
    LOG.info("Starting Ozone admin gateway");
    httpServer.start();
  }

  public void stop() throws Exception {
    LOG.info("Stoping Ozone admin gateway");
    httpServer.stop();
  }
}
