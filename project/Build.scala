import sbt._
import Keys._

object PigBuild extends Build {

  /*
   * Common Settings **********************************************************
   */
  lazy val commonSettings = Seq(
    version := "1.0",
    scalaVersion := "2.11.7",
    organization := "dbis"
  )
  
  /*
   * Projects *****************************************************************
   */
  lazy val root = (project in file(".")).
    configs(IntegrationTest).
    settings(commonSettings: _*).
    settings(Defaults.itSettings: _*).
    dependsOn(common).
    dependsOn(sparklib % "test;it").
    dependsOn(flinklib % "test;it"). 
    dependsOn(mapreduce % "test;it").
    aggregate(common, sparklib, flinklib,mapreduce) // remove this if you don't want to automatically build these projects when building piglet 

  lazy val common = (project in file("common")).
    settings(commonSettings: _*)

  lazy val sparklib = (project in file("sparklib")).
    settings(commonSettings: _*).
    dependsOn(common)

  lazy val flinklib = (project in file("flinklib")).
    settings(commonSettings: _*).
    dependsOn(common)

  lazy val mapreduce = (project in file("mapreduce")).
    settings(commonSettings: _*).
    dependsOn(common)
    

  /*
   * define the backend for the compiler: currently we support spark and flink
   */
  val backend = sys.props.getOrElse("backend", default="spark")
  
  val itDeps = backend match {
    case "flink" | "flinks" => Seq(Dependencies.flinkDist % "test;it" from Dependencies.flinkAddress)
    case "spark" | "sparks" => Seq(Dependencies.sparkCore % "test;it", Dependencies.sparkSql % "test;it")
    case "mapreduce" => Seq(Dependencies.pig % "test;it")
    case _ => println(s"Unsupported backend: $backend ! I don't know which dependencies to include!"); Seq.empty[ModuleID]
  }
  
  val itTests = backend match{
    case "flink" => Seq("dbis.test.flink.FlinkCompileIt")
    case "flinks" => Seq("dbis.test.flink.FlinksCompileIt")
    case "spark" => Seq("dbis.test.spark.SparkCompileIt")
    case "mapreduce" => Seq.empty[String] // TODO
    case _ => println(s"Unsupported backend: $backend - Will execute no tests"); Seq.empty[String]
  }
}

/*
 * Dependencies
 */
object Dependencies {
  // Libraries
  val scalaCompiler = "org.scala-lang" % "scala-compiler" %  "2.11.7"
  val scalaTest = "org.scalatest" %% "scalatest" % "2.2.0"
  val scalaParserCombinators = "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3"
  val scalaIoFile = "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.3-1"
  val jline = "jline" % "jline" % "2.12.1"
  val graphCore = "com.assembla.scala-incubator" %% "graph-core" % "1.9.2"
  val sparkCore = "org.apache.spark" %% "spark-core" % "1.4.1"
  val sparkSql = "org.apache.spark" %% "spark-sql" % "1.4.1"
//  val flinkDist = "org.apache.flink" %% "flink-dist" % "0.9.0"
  val flinkDist = "org.apache.flink" %% "flink-dist" % "0.10-SNAPSHOT"
  val scopt = "com.github.scopt" %% "scopt" % "3.3.0"
  val scalasti = "org.clapper" %% "scalasti" % "2.0.0"
  val jeromq = "org.zeromq" % "jeromq" % "0.3.4"
  val kiama = "com.googlecode.kiama" %% "kiama" % "1.8.0"
  val typesafe = "com.typesafe" % "config" % "1.3.0"
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0" 
  val log4j = "log4j" % "log4j" % "1.2.17"
  val slf4j= "org.slf4j" % "slf4j-simple" % "1.6.4"
  val hadoop = "org.apache.hadoop" % "hadoop-client" % "2.7.1"
  val pig = "org.apache.pig" % "pig" % "0.15.0"

//  val flinkAddress = "http://cloud01.prakinf.tu-ilmenau.de/flink-dist-0.9.0.jar"
  val flinkAddress = "file:///home/blaze/Masterthesis/projects/flink/build-target/lib/flink-dist-0.10-SNAPSHOT.jar"
  
  // Projects
  val rootDeps = Seq(
    jline,
    scalaTest % "test;it" withSources(),
    scalaParserCombinators withSources(),
    scalaCompiler,
    scopt,
    scalaIoFile,
    scalasti,
    kiama,
    typesafe,
    scalaLogging,
    log4j,
//    slf4j,
    hadoop % "provided"
  )
}
