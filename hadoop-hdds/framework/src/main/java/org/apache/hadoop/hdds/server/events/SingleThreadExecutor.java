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

package org.apache.hadoop.hdds.server.events;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.hadoop.hdds.utils.MetricsUtil;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple EventExecutor to call all the event handler one-by-one.
 *
 * @param <P> the payload type of events
 */
@Metrics(context = "EventQueue")
public class SingleThreadExecutor<P> implements EventExecutor<P> {

  private static final String EVENT_QUEUE = "EventQueue";

  private static final Logger LOG =
      LoggerFactory.getLogger(SingleThreadExecutor.class);

  private final String name;

  private final ExecutorService executor;

  @Metric
  private MutableCounterLong queued;

  @Metric
  private MutableCounterLong done;

  @Metric
  private MutableCounterLong failed;

  @Metric
  private MutableCounterLong scheduled;

  /**
   * Create SingleThreadExecutor.
   *
   * @param threadNamePrefix prefix prepended to thread names
   * @param name Unique name used in monitoring and metrics.
   */
  public SingleThreadExecutor(String name, String threadNamePrefix) {
    this.name = name;
    MetricsUtil.registerDynamic(this, EVENT_QUEUE + name,
        "Event Executor metrics ", "EventQueue");

    executor = Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setName(threadNamePrefix + EVENT_QUEUE + "-" + name);
          return thread;
        });
  }

  @Override
  public void onMessage(EventHandler<P> handler, P message, EventPublisher
      publisher) {
    queued.incr();
    executor.execute(() -> {
      scheduled.incr();
      try {
        handler.onMessage(message, publisher);
        done.incr();
      } catch (Exception ex) {
        LOG.error("Error on execution message {}", message, ex);
        failed.incr();
      }
    });
  }

  @Override
  public long failedEvents() {
    return failed.value();
  }

  @Override
  public long successfulEvents() {
    return done.value();
  }

  @Override
  public long queuedEvents() {
    return queued.value();
  }

  @Override
  public long scheduledEvents() {
    return scheduled.value();
  }

  @Override
  public void close() {
    executor.shutdown();
  }

  @Override
  public String getName() {
    return name;
  }
}
