/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.batch.table.validation

import org.apache.flink.table.api.{Tumble, ValidationException, _}
import org.apache.flink.table.planner.runtime.utils.JavaUserDefinedAggFunctions.OverAgg0
import org.apache.flink.table.planner.utils.TableTestBase

import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

class OverWindowValidationTest extends TableTestBase {

  /** OVER clause is necessary for [[OverAgg0]] window function. */
  @Test
  def testInvalidOverAggregation(): Unit = {
    val util = batchTestUtil()
    val t = util.addTableSource[(Int, Long, String)]("Table3", 'a, 'b, 'c)

    val overAgg = new OverAgg0
    assertThatExceptionOfType(classOf[ValidationException])
      .isThrownBy(() => t.select('c.count, overAgg('b, 'a)))
  }

  /** OVER clause is necessary for [[OverAgg0]] window function. */
  @Test
  def testInvalidOverAggregation2(): Unit = {
    val util = batchTestUtil()
    val table = util.addTableSource[(Long, Int, String)]('long, 'int, 'string)
    val overAgg = new OverAgg0
    assertThatExceptionOfType(classOf[ValidationException])
      .isThrownBy(
        () =>
          table
            .window(Tumble.over(5.milli).on('long).as('w))
            .groupBy('string, 'w)
            .select(overAgg('long, 'int)))
  }
}
