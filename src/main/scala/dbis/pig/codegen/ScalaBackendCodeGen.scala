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
package dbis.pig.codegen

import dbis.pig.tools.logging.PigletLogging

import dbis.pig.op._
import dbis.pig.op.cmd._
import dbis.pig.expr._
import dbis.pig.backends.BackendManager
import dbis.pig.plan.DataflowPlan
import dbis.pig.schema._
import dbis.pig.udf._

import java.net.URI
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Set


// import scala.collection.mutable.Map

/**
 * Implements a code generator for Scala-based backends such as Spark or Flink which use
 * a template file for the backend-specific code.
 *
 * The main idea of the generated code is the following: a record of the backend-specific data
 * structure (e.g. RDD in Spark) is represented by an instance of a specific case class which is
 * generated according to the schema of an operator.
 *
 * @param template the name of the backend-specific template fle
 */
abstract class ScalaBackendCodeGen(template: String) extends CodeGeneratorBase with PigletLogging {

  templateFile = template 
  /*------------------------------------------------------------------------------------------------- */
  /*                                           helper functions                                       */
  /*------------------------------------------------------------------------------------------------- */

  def schemaClassName(name: String) = s"_${name}_Tuple"

  /**
   *
   * @param schema
   * @param ref
   * @return
   */
  def tupleSchema(schema: Option[Schema], ref: Ref): Option[Schema] = {
    val tp = ref match {
      case nf @ NamedField(f, _) => schema match {
        case Some(s) => if (f == s.element.name) s.element.valueType else s.field(nf).fType
        case None => throw new SchemaException(s"unknown schema for field $f")
      }
      case PositionalField(p) => schema match {
        case Some(s) => s.field(p).fType
        case None => None
      }
      case _ => None
    }
    if (tp == None)
      None
    else
      Some(new Schema( if (tp.isInstanceOf[BagType]) tp.asInstanceOf[BagType] else BagType(tp.asInstanceOf[TupleType])))
  }

  /**
   * Replaces Pig-style quotes (') by double quotes in a string.
   *
   * @param s a quoted string in Pig-style (')
   * @return a string with double quotes
   */
  def quote(s: String): String = s.replace('\'', '"')

  /**
   * Returns true if the sort order of the OrderBySpec is ascending
   *
   * @param spec the OrderBySpec value
   * @return true if sorting in ascending order
   */
  def ascendingSortOrder(spec: OrderBySpec): String = if (spec.dir == OrderByDirection.AscendingOrder) "true" else "false"


  // TODO: complex types
  val scalaTypeMappingTable = Map[PigType, String](
    Types.IntType -> "Int",
    Types.LongType -> "Long",
    Types.FloatType -> "Float",
    Types.DoubleType -> "Double",
    Types.CharArrayType -> "String",  
    Types.ByteArrayType -> "String", //TODO: check this
    Types.AnyType -> "String") //TODO: check this

  /**
   * Returns the name of the Scala type for representing the given field. If the schema doesn't exist we assume
   * bytearray which is mapped to String.
   *
   * @param field a Ref representing the field (positional or named=
   * @param schema the schema of the field
   * @return the name of the Scala type
   */
  def scalaTypeOfField(field: Ref, schema: Option[Schema]) : String = {
    schema match {
      case Some(s) => {
        field match {
          case PositionalField(f) => scalaTypeMappingTable(s.field(f).fType)
          case nf @ NamedField(_, _) => scalaTypeMappingTable(s.field(nf).fType)
          case _ => "String"
        }
      }
      case None => "String"
    }
  }

  /**
   *
   * @param schema
   * @return
   */
  def listToTuple(schema: Option[Schema]): String = schema match {
    case Some(s) => '"' + (0 to s.fields.length-1).toList.map(i => if (s.field(i).isBagType) s"{%s}" else s"%s").mkString(",") + '"' +
      ".format(" + (0 to s.fields.length-1).toList.map(i =>
      if (s.field(i).isBagType)
        s"""t($i).asInstanceOf[Seq[Any]].mkString(",")"""
      else
        s"t($i)").mkString(",") + ")"
    case None => s"t(0)"
  }

  /**
   * Find the index of the field represented by the reference in the given schema.
   * The reference could be a named field or a positional field. If not found -1 is returned.
   *
   * @param schema the schema containing the field
   * @param field the field denoted by a Ref object
   * @return the index of the field
   */
  def findFieldPosition(schema: Option[Schema], field: Ref): Int = field match {
    case nf @ NamedField(f, _) => schema match {
      case Some(s) => if (f == s.element.name) 0 else s.indexOfField(nf)
      case None => -1
    }
    case PositionalField(p) => p
    case _ => -1
  }


  /*------------------------------------------------------------------------------------------------- */
  /*                                  Scala-specific code generators                                  */
  /*------------------------------------------------------------------------------------------------- */

   /**
    * Generate Scala code for a reference to a named field, a positional field or a tuple/map derefence.
    *
    * @param schema the (optional) schema describing the tuple structure
    * @param ref the reference object
    * @param tuplePrefix the variable name
    * @param aggregate
    * @return the generated code
    */
  def emitRef(schema: Option[Schema],
              ref: Ref,
              tuplePrefix: String = "t",
              aggregate: Boolean = false,
              namedRef: Boolean = false): String = ref match {
    case nf @ NamedField(f, _) => if (namedRef) {
      // check if f exists in the schema
      schema match {
        case Some(s) => {
          val p = s.indexOfField(nf)
          if (p != -1)
            s"$tuplePrefix._$p"
          else
            f // TODO: check whether thus is a valid field (or did we check it already in checkSchemaConformance??)
        }
        case None =>
          // if we don't have a schema this is not allowed
          throw new TemplateException(s"invalid field name $f")
      }
    }
    else {
      val pos = schema.get.indexOfField(nf)
      if (pos == -1)
        throw new TemplateException(s"invalid field name $f")
      s"$tuplePrefix._$pos" // s"$tuplePrefix.$f"
    }
    case PositionalField(pos) => schema match {
      case Some(s) => s"$tuplePrefix._$pos"
      case None =>
        // if we don't have a schema the Record class is used
        s"$tuplePrefix.get($pos)"
    }
    case Value(v) => v.toString
    // case DerefTuple(r1, r2) => s"${emitRef(schema, r1)}.asInstanceOf[List[Any]]${emitRef(schema, r2, "")}"
    // case DerefTuple(r1, r2) => s"${emitRef(schema, r1, "t", false)}.asInstanceOf[List[Any]]${emitRef(tupleSchema(schema, r1), r2, "", false)}"
    case DerefMap(m, k) => s"${emitRef(schema, m)}(${k})"
    case _ => { "" }
  }

  /**
   * Generate Scala code for a predicate on expressions.
   *
   * @param schema the optional input schema of the operator where the expressions refer to.
   * @param predicate the actual predicate
   * @return a string representation of the generated Scala code
   */
  def emitPredicate(schema: Option[Schema], predicate: Predicate): String = predicate match {
    case Eq(left, right) => { s"${emitExpr(schema, left)} == ${emitExpr(schema, right)}"}
    case Neq(left, right) => { s"${emitExpr(schema, left)} != ${emitExpr(schema, right)}"}
    case Leq(left, right) => { s"${emitExpr(schema, left)} <= ${emitExpr(schema, right)}"}
    case Lt(left, right) => { s"${emitExpr(schema, left)} < ${emitExpr(schema, right)}"}
    case Geq(left, right) => { s"${emitExpr(schema, left)} >= ${emitExpr(schema, right)}"}
    case Gt(left, right) => { s"${emitExpr(schema, left)} > ${emitExpr(schema, right)}"}
    case And(left, right) => s"${emitPredicate(schema, left)} && ${emitPredicate(schema, right)}"
    case Or(left, right) => s"${emitPredicate(schema, left)} || ${emitPredicate(schema, right)}"
    case Not(pred) => s"!(${emitPredicate(schema, pred)})"
    case PPredicate(pred) => s"(${emitPredicate(schema, pred)})"
    case _ => { s"UNKNOWN PREDICATE: $predicate" }
  }

  /**
   * Generates Scala code for a grouping expression in GROUP BY. We construct code for map
   * in the form "map(t => {(t(0),t(1),...)}" if t(0), t(1) are grouping attributes.
   *
   * @param schema the optional input schema of the operator where the expressions refer to.
   * @param groupingExpr the actual grouping expression object
   * @return a string representation of the generated Scala code
   */
  def emitGroupExpr(schema: Option[Schema], groupingExpr: GroupingExpression): String = {
    if (groupingExpr.keyList.size == 1)
      groupingExpr.keyList.map(e => emitRef(schema, e)).mkString
    else
      "(" + groupingExpr.keyList.map(e => emitRef(schema, e)).mkString(",") + ")"
  }

  /**
   *
   * @param schema
   * @param joinExpr
   * @return
   */
  def emitJoinKey(schema: Option[Schema], joinExpr: List[Ref]): String = {
    if (joinExpr.size == 1)
      emitRef(schema, joinExpr.head)
    else
      s"Array(${joinExpr.map(e => emitRef(schema, e)).mkString(",")}).mkString"
  }

  /**
    * Generate Scala code for a function call with parameters.
    *
    * @param f the function name
    * @param params the list of parameters
    * @param schema the schema
    * @return the generated Scala code
    */
  def emitFuncCall(f: String, params: List[ArithmeticExpr], schema: Option[Schema], namedRef: Boolean): String = {
    val pTypes = params.map(p => p.resultType(schema))
    UDFTable.findUDF(f, pTypes) match {
      case Some(udf) => {
        // println(s"udf: $f found: " + udf)
        if (udf.isAggregate) {
          s"${udf.scalaName}(${emitExpr(schema, params.head, aggregate = true, namedRef = namedRef)})"
        }
        else {
          val mapStr = if (udf.resultType.isInstanceOf[ComplexType]) {
            udf.resultType match {
              case BagType(v) => s".map(${schemaClassName(v.className)}(_))"
              case _ => "" // TODO: handle TupleType and MapType
            }
          } else ""
          val paramExprList = params.zipWithIndex.map { case (e, i) =>
            // if we know the expected parameter type and the expression type
            // is a generic bytearray then we cast it to the expected type
            val typeCast = if (udf.paramTypes.length > i && // make sure the function has enough parameters
              e.resultType(schema) == Types.ByteArrayType &&
              (udf.paramTypes(i) != Types.ByteArrayType && udf.paramTypes(i) != Types.AnyType)) {
              s".asInstanceOf[${scalaTypeMappingTable(udf.paramTypes(i))}]"
            } else ""
            emitExpr(schema, e, namedRef = namedRef) + typeCast
          }

          s"${udf.scalaName}(${paramExprList.mkString(",")})${mapStr}"
        }
      }
      case None => {
        // println(s"udf: $f not found")
        // check if we have have an alias in DataflowPlan
        if (udfAliases.nonEmpty && udfAliases.get.contains(f)) {
          val alias = udfAliases.get(f)
          val paramList = alias._2 ::: params.map(e => emitExpr(schema, e, namedRef = namedRef))
          s"${alias._1}(${paramList.mkString(",")})"
        }
        else {
          // we don't know the function yet, let's assume there is a corresponding Scala function
          s"$f(${params.map(e => emitExpr(schema, e, namedRef = namedRef)).mkString(",")})"
        }
      }
    }
  }

  /**
   *
   * @param schema
   * @param expr
   * @return
   */
  def emitExpr(schema: Option[Schema],
               expr: ArithmeticExpr,
               aggregate: Boolean = false,
               namedRef: Boolean = false): String = expr match {
    case CastExpr(t, e) => {
      // TODO: check for invalid type
      val targetType = scalaTypeMappingTable(t)
      s"${emitExpr(schema, e, namedRef = namedRef)}.to$targetType"
    }
    case PExpr(e) => s"(${emitExpr(schema, e, namedRef = namedRef)})"
    case MSign(e) => s"-${emitExpr(schema, e, namedRef = namedRef)}"
    case Add(e1, e2) => s"${emitExpr(schema, e1, namedRef = namedRef)} + ${emitExpr(schema, e2, namedRef = namedRef)}"
    case Minus(e1, e2) => s"${emitExpr(schema, e1, namedRef = namedRef)} - ${emitExpr(schema, e2, namedRef = namedRef)}"
    case Mult(e1, e2) => s"${emitExpr(schema, e1, namedRef = namedRef)} * ${emitExpr(schema, e2, namedRef = namedRef)}"
    case Div(e1, e2) => s"${emitExpr(schema, e1, namedRef = namedRef)} / ${emitExpr(schema, e2, namedRef = namedRef)}"
    case RefExpr(e) => s"${emitRef(schema, e, "t", aggregate, namedRef = namedRef)}"
    case Func(f, params) => emitFuncCall(f, params, schema, namedRef)
    case FlattenExpr(e) => flattenExpr(schema, e)
    case ConstructTupleExpr(exprs) => {
      val exType = expr.resultType(schema).asInstanceOf[TupleType]
      val s = Schema(new BagType(exType))
      s"${schemaClassName(s.className)}(${exprs.map(e => emitExpr(schema, e, namedRef = namedRef)).mkString(",")})"
    }
    case ConstructBagExpr(exprs) => {
      val exType = expr.resultType(schema).asInstanceOf[BagType]
      val s = Schema(exType)
      s"List(${exprs.map(e => s"${schemaClassName(s.className)}(${emitExpr(schema, e, namedRef = namedRef)})").mkString(",")})"
    }
    case ConstructMapExpr(exprs) => {
      val exType = expr.resultType(schema).asInstanceOf[MapType]
      val valType = exType.valueType
      val exprList = exprs.map(e => emitExpr(schema, e, namedRef = namedRef))
      // convert the list (e1, e2, e3, e4) into a list of (e1 -> e2, e3 -> e4)
      val mapStr = exprList.zip(exprList.tail).zipWithIndex.filter{
        case (p, i) => i % 2 == 0
      }.map{case (p, i) => s"${p._1} -> ${p._2}"}.mkString(",")
      s"Map[String,${scalaTypeMappingTable(valType)}](${mapStr})"
    }
    case _ => println("unsupported expression: " + expr); ""
  }

  /**
   * Constructs the Scala code for flattening a tuple. We have to determine the field in the
   * input schema refering to a tuple type and extract all fields of this tuple type.
   *
   * @param schema the input schema of the FOREACH operator
   * @param e the expression to be flattened (should be a RefExpr)
   * @return a string representation of the Scala code
   */
  def flattenExpr(schema: Option[Schema], e: ArithmeticExpr): String = {
    if (schema.isEmpty) throw new TemplateException("cannot flatten a tuple without a schema")
    // we need the field name used in Scala (either the actual name or _<position>) as well as
    // the actual field
    val (refName, field) = e match {
      case RefExpr(r) => r match {
        case nf@NamedField(n, _) => ("_" + schema.get.indexOfField(nf), schema.get.field(nf))
        case PositionalField(p) => ("_" + p.toString, schema.get.field(p))
          // either a named or a positional field: all other cases are not allowed!?
        case _ => throw new TemplateException("invalid flatten expression: argument isn't a reference")
      }
      case _ => throw new TemplateException(s"invalid flatten expression: argument $e isn't a reference")
    }
    if (field.fType.tc == TypeCode.TupleType) {
      // we flatten a tuple
      val tupleType = field.fType.asInstanceOf[TupleType]
      // finally, produce a list of t.<refName>.<fieldPos>
      tupleType.fields.zipWithIndex.map { case (f, i) => s"t.${refName}._$i" }.mkString(", ")
    }
    else if (field.fType.tc == TypeCode.BagType) {
      // we flatten a bag
      val bagType = field.fType.asInstanceOf[BagType]
      s"t.${refName}"
    }
    else
      // other types than tuple and bag cannot be flattened
      throw new TemplateException("invalid flatten expression: argument doesn't refer to a tuple or bag")
  }

  /**
   * Constructs the GENERATE expression list in FOREACH.
   *
   * @param schema the input schema
   * @param genExprs the list of expressions in the GENERATE clause
   * @return a string representation of the Scala code
   */
  def emitGenerator(schema: Option[Schema], genExprs: List[GeneratorExpr], namedRef: Boolean = false): String = {
    s"${genExprs.map(e => emitExpr(schema, e.expr, aggregate = false, namedRef = namedRef)).mkString(", ")}"
  }

  /**
   * Creates the Scala code needed for a flatten expression where the argument is a bag.
   * It requires a flatMap transformation.
   *
   * @param node the FOREACH operator containing the flatten in the GENERATE clause
   * @param genExprs the list of generator expressions
   * @return a string representation of the Scala code
   */
  def emitBagFlattenGenerator(node: PigOperator, genExprs: List[GeneratorExpr]): String = {
    require(node.schema.isDefined)
    val className = schemaClassName(node.schema.get.className)
    // extract the flatten expression from the generator list
    val flattenExprs = genExprs.filter(e => e.expr.traverseOr(node.inputSchema.getOrElse(null), Expr.containsFlattenOnBag))
    // determine the remaining expressions
    val otherExprs = genExprs.diff(flattenExprs)
    if (flattenExprs.size == 1) {
      // there is only a single flatten expression
      val ex: FlattenExpr = flattenExprs.head.expr.asInstanceOf[FlattenExpr]
      if (otherExprs.nonEmpty) {
        // we have to cross join the flatten expression with the others:
        // t._1.map(s => <class>(<expr))
        val exs = otherExprs.map(e => emitExpr(node.inputSchema, e.expr)).mkString(",")
        s"${emitExpr(node.inputSchema, ex)}.map(s => ${className}($exs, s))"
      }
      else {
        // there is no other expression: we just construct an expression for flatMap:
        // (<expr>).map(t => <class>(t))
        s"${emitExpr(node.inputSchema, ex.a)}).map(t => ${className}(t._0))"
      }
    }
    else
      s"" // i.flatMap(t => t(1).asInstanceOf[Seq[Any]].map(s => List(t(0),s)))
  }

  /**
   *
   * @param schema the input schema of the operator
   * @param params the list of parameters (as Refs)
   * @return the generated code
   */
  def emitParamList(schema: Option[Schema], params: Option[List[Ref]]): String = params match {
    case Some(refList) => if (refList.nonEmpty) s",${refList.map(r => emitRef(schema, r)).mkString(",")}" else ""
    case None => ""
  }

  /**
   *
   * @param schema
   * @param orderSpec
   * @param out
   * @param in
   * @return
   */
  def emitSortKey(schema: Option[Schema], orderSpec: List[OrderBySpec], out: String, in: String) : String = {
    if (orderSpec.size == 1)
      emitRef(schema, orderSpec.head.field)
    else
      s"custKey_${out}_${in}(${orderSpec.map(r => emitRef(schema, r.field)).mkString(",")})"
  }
  
  val castMethods = Set.empty[String]

  /**
    * Create helper class for operators such as ORDER BY.
    *
    * @param node the Pig operator requiring helper code
    * @return a string representing the helper code
    */
  def emitHelperClass(node: PigOperator): String = {
    node match {
      case OrderBy(out, in, orderSpec, _) => {
        val num = orderSpec.length
        /**
          * Emit the comparison expression used in in the orderHelper class
          *
          * @param col the current position of the comparison field
          * @return the expression code
          */
        def genCmpExpr(col: Int): String = {
          val cmpStr = if (orderSpec(col - 1).dir == OrderByDirection.AscendingOrder)
            s"this.c$col compare that.c$col"
          else s"that.c$col compare this.c$col"
          if (col == num) s"{ $cmpStr }"
          else s"{ if (this.c$col == that.c$col) ${genCmpExpr(col + 1)} else $cmpStr }"
        }

        var params = Map[String, Any]()
        //Spark
        params += "cname" -> s"custKey_${node.outPipeName}_${node.inPipeName}"
        var col = 0
        params += "fields" -> orderSpec.map(o => {
          col += 1;
          s"c$col: ${scalaTypeOfField(o.field, node.schema)}"
        }).mkString(", ")
        params += "cmpExpr" -> genCmpExpr(1)

        //Flink??
        params += "out" -> node.outPipeName
        params += "key" -> orderSpec.map(r => emitRef(node.schema, r.field)).mkString(",")
        if (ascendingSortOrder(orderSpec.head) == "false") params += "reverse" -> true

        callST("orderHelper", Map("params" -> params))
      }
      case Top(_, _, orderSpec, _) => {
        val size = orderSpec.size
        var params = Map[String, Any]()
        val hasSchema = node.inputSchema.isDefined

        val schemaClass = if (!hasSchema) {
          "Record"
        } else {
          schemaClassName(node.schema.get.className)
        }

        params += "schemaclass" -> schemaClass

        def genCmpExpr(col: Int): String = {
          var firstGetter = "first."
          var secondGetter = "second."
          if (ascendingSortOrder(orderSpec(col)) == "true") {
            // If we're not sorting ascending, reverse the getters so the ordering gets reversed
            firstGetter = "second."
            secondGetter = "first."
          }

          if (!hasSchema) {
            firstGetter += "get"
            secondGetter += "get"
          }

          if (hasSchema) {
            if (col == (size - 1))
              s"{ ${firstGetter}_$col compare ${secondGetter}_$col }"
            else
              s"{ if (${firstGetter}_$col == ${secondGetter}_$col) ${genCmpExpr(col + 1)} else ${firstGetter}_$col compare " +
                s"${secondGetter}_$col }"
          } else {
            if (col == (size - 1))
              s"{ $firstGetter($col) compare $secondGetter($col) }"
            else
              s"{ if ($firstGetter($col) == $secondGetter($col)) ${genCmpExpr(col + 1)} else $firstGetter($col) compare " +
                s"$secondGetter($col) }"
          }
        }

        //Spark
        params += "cname" -> s"custKey_${node.outPipeName}_${node.inPipeName}"
        var col = 0
        params += "fields" -> orderSpec.map(o => {
          col += 1;
          s"c$col: ${scalaTypeOfField(o.field, node.schema)}"
        }).mkString(", ")
        params += "cmpExpr" -> genCmpExpr(0)

        //Flink
        params += "out" -> node.outPipeName
        params += "key" -> orderSpec.map(r => emitRef(node.schema, r.field)).mkString(",")
        if (ascendingSortOrder(orderSpec.head) == "false") params += "reverse" -> true
        callST("topHelper", Map("params" -> params))
      }
      case _ => ""
    }
  }


  /**
    * Construct the extract function for the LOAD operator.
    *
    * @param node the PigOperator for loading data
    * @param loaderFunc the loader function
    * @return a parameter map with class and extractor elements
    */
  def emitExtractorFunc(node: PigOperator, loaderFunc: Option[String]): Map[String, Any] = {
    def schemaExtractor(schema: Schema): String =
      schema.fields.zipWithIndex.map{case (f, i) =>
        // we cannot perform a "toAny" - therefore, we treat bytearray as String here
        val t = scalaTypeMappingTable(f.fType); s"data($i).to${if (t == "Any") "String" else t}"
      }.mkString(", ")

    def jdbcSchemaExtractor(schema: Schema): String =
      schema.fields.zipWithIndex.map{case (f, i) => s"data.get${scalaTypeMappingTable(f.fType)}($i)"}.mkString(", ")

    var paramMap = Map[String, Any]()
    node.schema match {
      case Some(s) => if (loaderFunc.nonEmpty && loaderFunc.get == "JdbcStorage")
        // JdbcStorage provides already types results, therefore we need an extractor which calls
        // only the appropriate get functions on sql.Row
          paramMap += ("extractor" ->
            s"""(data: org.apache.spark.sql.Row) => ${schemaClassName(s.className)}(${jdbcSchemaExtractor(s)})""",
            "class" -> schemaClassName(s.className))
        else
          paramMap += ("extractor" ->
            s"""(data: Array[String]) => ${schemaClassName(s.className)}(${schemaExtractor(s)})""",
            "class" -> schemaClassName(s.className))
      case None => {
        paramMap += ("extractor" -> "(data: Array[String]) => Record(data)", "class" -> "Record")
      }
    }
    paramMap
  }

  /*------------------------------------------------------------------------------------------------- */
  /*                                   Node code generators                                           */
  /*------------------------------------------------------------------------------------------------- */

  /**
   * Generates code for the LOAD operator.
   *
   * @param node the load node operator itself
   * @param file the name of the file to be loaded
   * @param loaderFunc an optional loader function (we assume a corresponding Scala function is available)
   * @param loaderParams an optional list of parameters to a loader function (e.g. separators)
   * @return the Scala code implementing the LOAD operator
   */
  def emitLoad(node: PigOperator, file: URI, loaderFunc: Option[String], loaderParams: List[String]): String = {
    var paramMap = emitExtractorFunc(node, loaderFunc)
    paramMap += ("out" -> node.outPipeName)
    paramMap += ("file" -> file.toString)
    if (loaderFunc.isEmpty)
      paramMap += ("func" -> BackendManager.backend.defaultConnector)
    else {
      paramMap += ("func" -> loaderFunc.get)
      if (loaderParams != null && loaderParams.nonEmpty)
        paramMap += ("params" -> loaderParams.mkString(","))
    }
    callST("loader", paramMap)
  }

  /**
   * Generates code for the STORE operator.
   *
   * @param node the STORE operator
   * @param file the URI of the target file
   * @param storeFunc a storage function
   * @param params an (optional) parameter list for the storage function
   * @return the Scala code implementing the STORE operator
   */
  def emitStore(node: PigOperator, file: URI, storeFunc: Option[String], params: List[String]): String = {
    var paramMap = Map("in" -> node.inPipeName,
      "file" -> file.toString,
      "func" -> storeFunc.getOrElse(BackendManager.backend.defaultConnector))
    node.schema match {
      case Some(s) => paramMap += ("class" -> schemaClassName(s.className))
      case None => paramMap += ("class" -> "Record")
    }

    if (params != null && params.nonEmpty)
      paramMap += ("params" -> params.mkString(","))

    callST("store", paramMap)
  }

  def tableHeader(schema: Option[Schema]): String = schema match {
    case Some(s) => s.fields.map(f => f.name).mkString("\t")
    case None => ""
  }

  /**
    * Generates the code for the STREAM THROUGH operator including
    * the necessary conversion of input and output data.
    *
    * @param node the StreamOp operator
    * @return the Scala code implementing the operator
    */
  def emitStreamThrough(node: StreamOp): String = {
    // TODO: how to handle cases where no schema was given??
    val className = schemaClassName(node.schema.get.className)

    val inFields = node.inputSchema.get.fields.zipWithIndex.map{ case (f, i) => s"t._$i"}.mkString(", ")
    val outFields = node.schema.get.fields.zipWithIndex.map{ case (f, i) => s"t($i)"}.mkString(", ")

    callST("streamOp",
      Map("out" -> node.outPipeName,
          "op" -> node.opName,
          "in" -> node.inPipeName,
          "class" -> className,
          "in_fields" -> inFields,
          "out_fields" -> outFields,
          "params" -> emitParamList(node.schema, node.params)))
  }

  /*------------------------------------------------------------------------------------------------- */
  /*                           implementation of the GenCodeBase interface                            */
  /*------------------------------------------------------------------------------------------------- */

  /**
   * Generate code for a class representing a schema type.
   *
   * @param schema the schema for which we generate a class
   * @return a string representing the code
   */
  def emitSchemaClass(schema: Schema): String = {
    def typeName(f: PigType, n: String) = scalaTypeMappingTable.get(f) match {
      case Some(n) => n
      case None => f match {
        // if we have a bag without a name then we assume that we have got
        // a case class with _<field_name>_Tuple
        case BagType(v) => s"Iterable[_${v.className}_Tuple]"
        case TupleType(f, c) => schemaClassName(c)
        case MapType(v) => s"Map[String,${scalaTypeMappingTable(v)}]"
        case _ => f.descriptionString
      }
    }
    val fields = schema.fields.toList
    // build the list of field names (_0, ..., _n)
    val fieldStr = fields.zipWithIndex.map{ case (f, i) =>
           s"_$i : ${typeName(f.fType, f.name)}"}.mkString(", ")

    // construct the mkString method
    //   we have to handle the different types here:
    //      TupleType -> ()
    //      BagType -> {}
    val toStr = fields.zipWithIndex.map{
      case (f, i) => f.fType match {
        case BagType(_) => s""""{" + _$i.mkString(",") + "}""""
        case MapType(v) => s""""[" + _$i.map{ case (k,v) => s"$$k#$$v" }.mkString(",") + "]""""
        case _ => s"_$i" + (if (f.fType.tc != TypeCode.CharArrayType && fields.length == 1) ".toString" else "")
      }
    }.mkString(" + _c + ")

    callST("schema_class", Map("name" -> schemaClassName(schema.className),
                              "fields" -> fieldStr,
                              "string_rep" -> toStr))
  }

  /**
   * Generate code for the given Pig operator.
   *
   * @param node the operator (an instance of PigOperator)
   * @return a string representing the code
   */
  def emitNode(node: PigOperator): String = {
    node.checkPipeNames
    node match {
        /*
         * NOTE: Don't use "out" here -> it refers only to initial constructor argument but isn't consistent
         *       after changing the pipe name. Instead, use node.outPipeName
         */
      case Load(out, file, schema, func, params) => emitLoad(node, file, func, params)
      case Dump(in) => callST("dump", Map("in"->node.inPipeName))
      case Display(in) => callST("display", Map("in"->node.inPipeName, "tableHeader"->tableHeader(node.inputSchema)))
      case Store(in, file, func, params) => emitStore(node, file, func, params)
      case Describe(in) => s"""println("${node.schemaToString}")"""
      case SplitInto(in, splits) => callST("splitInto", Map("in"->node.inPipeName, "out"->node.outPipeNames, "pred"->splits.map(s => emitPredicate(node.schema, s.expr))))
      case Union(out, rels) => callST("union", Map("out"->node.outPipeName,"in"->node.inPipeName,"others"->node.inPipeNames.tail))
      case Sample(out, in, expr) => callST("sample", Map("out"->node.outPipeName,"in"->node.inPipeName,"expr"->emitExpr(node.schema, expr)))
      case sOp@StreamOp(out, in, op, params, schema) => emitStreamThrough(sOp)
      // case MacroOp(out, name, params) => callST("call_macro", Map("out"->node.outPipeName,"macro_name"->name,"params"->emitMacroParamList(node.schema, params)))
      case HdfsCmd(cmd, params) => callST("fs", Map("cmd"->cmd, "params"->params))
      case RScript(out, in, script, schema) => callST("rscript", Map("out"->node.outPipeName,"in"->node.inputs.head.name,"script"->quote(script)))
      case ConstructBag(in, ref) => "" // used only inside macros
      case DefineMacroCmd(_, _, _, _) => "" // code is inlined in MacroOp; no need to generate it here again
      case Delay(out, in, size, wtime) => callST("delay", Map("out" -> node.outPipeName, "in"->node.inPipeName, "size"->size, "wait"->wtime)) 
      case Empty(_) => ""
      case _ => throw new TemplateException(s"Template for node '$node' not implemented or not found")
    }
  }

   /**
   * Generate code needed for importing required Scala packages.
   *
   * @return a string representing the import code
   */
  def emitImport: String = callST("init_code")

  /**
   * Generate code for the header of the script outside the main class/object,
   * i.e. defining the main object.
   *
   * @param scriptName the name of the script (e.g. used for the object)
   * @param additionalCode Scala source code that was embedded into the script
   * @return a string representing the header code
   */
  def emitHeader1(scriptName: String, additionalCode: String = ""): String =
    callST("query_object", Map("name" -> scriptName, "embedded_code" -> additionalCode))

  /**
   *
   * Generate code for the header of the script which should be defined inside
   * the main class/object.
   *
   * @param scriptName the name of the script (e.g. used for the object)
   * @return a string representing the header code
   */
  def emitHeader2(scriptName: String, enableProfiling: Boolean = false): String = {
    var map = Map("name" -> scriptName)
    
    if(enableProfiling)
      map += ("profiling" -> "profiling")
    
    callST("begin_query", map )
  }

  /**
   * Generate code needed for finishing the script and starting the execution.
   *
   * @return a string representing the end of the code.
   */
  def emitFooter: String = callST("end_query", Map("name" -> "Starting Query"))

}