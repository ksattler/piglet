/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dbis.piglet.plan.rewriting.dsl.builders

import dbis.piglet.op.PigOperator
import dbis.piglet.plan.rewriting.Rewriter
import dbis.piglet.plan.rewriting.dsl.traits.BuilderT

import scala.reflect.ClassTag

/** A builder for applying a rewriting method that rewrites a single [[dbis.piglet.op.PigOperator]] to another one.
 *
 * @tparam FROM
 * @tparam TO
 */
class ReplacementBuilder[FROM <: PigOperator : ClassTag, TO <: PigOperator : ClassTag] extends
  PigOperatorBuilder[FROM, TO] {
  override def wrapInFixer(func: (FROM => Option[TO])): (FROM => Option[TO]) = func

  override def addAsStrategy(func: (FROM => Option[TO])) = {
    Rewriter.addTypedStrategy(func)
  }
}
