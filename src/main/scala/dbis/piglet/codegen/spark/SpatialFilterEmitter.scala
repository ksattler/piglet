package dbis.piglet.codegen.spark

import dbis.piglet.codegen.{CodeEmitter, CodeGenContext}
import dbis.piglet.codegen.scala_lang.ScalaEmitter
import dbis.piglet.op.IndexMethod.IndexMethod
import dbis.piglet.op.SpatialFilter

class SpatialFilterEmitter extends CodeEmitter[SpatialFilter] {
  
  override def template =
    """val <out> = <in><keyby><liveindex>.<predicate>(<other>).map{ case (_,v) =>
      |  <if (profiling)>
      |    PerfMonitor.sampleSize(v,"<lineage>", accum, randFactor)
      |  <endif>
      |  v
      |}""".stripMargin
  
  def indexTemplate(idxConfig: Option[(IndexMethod, List[String])]) = idxConfig match {
    case Some((method, params)) => CodeEmitter.render(".liveIndex(<params>)", Map("params" -> params.mkString(",")))
    case None => ""
  }
  
  override def code(ctx: CodeGenContext, op: SpatialFilter): String = {

//    println(op.inputSchema.map(_.element.valueType))
    render(Map("out" -> op.outPipeName,
      "in" -> op.inPipeName,
      "predicate" -> op.pred.predicateType.toString.toLowerCase(),
      "other" -> ScalaEmitter.emitExpr(ctx, op.pred.expr),
      "liveindex" -> indexTemplate(op.idx),
      "keyby" -> {
        if(op.schema.nonEmpty && op.schema.get.isIndexed)
          ""
        else
          SpatialEmitterHelper.keyByCode(op.inputSchema, op.pred.field, ctx)
      },
      "lineage" -> op.lineageSignature
      //          {
      //            if(SpatialEmitterHelper.geomIsFirstPos(op.pred.field, op))
      //              ""
      //            else
      //              s".keyBy(${ctx.asString("tuplePrefix")} => ${ScalaEmitter.emitRef(CodeGenContext(ctx,Map("schema"->op.inputSchema)), op.pred.field)})"
      //          }
    ))
  }
  
}

object SpatialFilterEmitter {
	lazy val instance = new SpatialFilterEmitter
}