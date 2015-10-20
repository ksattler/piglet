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
package dbis.pig.plan.rewriting

import dbis.pig.op.PigOperator

object Functions {
  def replace[T <: PigOperator, T2 <: PigOperator](old: T, new_ : T2): T2 =
    Rewriter.fixReplacement(old) (new_)

  def swap[T <: PigOperator, T2 <: PigOperator](parent: T, child: T2): T2 =
    Rewriter.fixInputsAndOutputs(parent, child, child, parent)
}
