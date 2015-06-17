/*
 * Copyright (c) 2015 The Piglet team,
 *                    All Rights Reserved.
 *
 * This file is part of the Piglet package.
 *
 * PipeFabric is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License (GPL) as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This package is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; see the file LICENSE.
 * If not you can find the GPL at http://www.gnu.org/copyleft/gpl.html
 */
package dbis.pig.op

import dbis.pig.schema.Schema

/**
 * Load represents the LOAD operator of Pig.
 *
 * @param initialOutPipeName the name of the initial output pipe (relation).
 * @param file the name of the file to be loaded
 * @param loadSchema
 * @param loaderFunc
 * @param loaderParams
 */
case class Load(override val initialOutPipeName: String, file: String,
                var loadSchema: Option[Schema] = None,
                loaderFunc: String = "", loaderParams: List[String] = null) extends PigOperator(initialOutPipeName, List(), loadSchema) {
  override def constructSchema: Option[Schema] = {
    /*
     * Either the schema was defined or it is None.
     */
    schema
  }

  /**
   * Returns the lineage string describing the sub-plan producing the input for this operator.
   *
   * @return a string representation of the sub-plan.
   */
  override def lineageString: String = {
    s"""LOAD%${file}%""" + super.lineageString
  }
}