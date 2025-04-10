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

package org.apache.hadoop.hdds.utils.db;

import java.io.IOException;

/**
 * Create and commit batch operation for one DB.
 */
public interface BatchOperationHandler {

  /**
   * Initialize an atomic batch operation which can hold multiple PUT/DELETE
   * operations and committed later in one step.
   *
   * @return BatchOperation holder which can be used to add or commit batch
   * operations.
   */
  BatchOperation initBatchOperation();

  /**
   * Commit the batch operations.
   *
   * @param operation which contains all the required batch operation.
   * @throws IOException on Failure.
   */
  void commitBatchOperation(BatchOperation operation) throws IOException;
}
