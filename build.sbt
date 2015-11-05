import sbt.Keys._
import sbt._

name := "Piglet"

libraryDependencies ++= Dependencies.rootDeps

libraryDependencies ++= itDeps

mainClass in (Compile, packageBin) := Some("dbis.pig.PigREPL")

mainClass in (Compile, run) := Some("dbis.pig.PigCompiler")

assemblyJarName in assembly := "PigCompiler.jar"

mainClass in assembly := Some("dbis.pig.PigCompiler")

test in assembly := {}

// needed for serialization/deserialization
fork in Test := true

fork in IntegrationTest := false

// scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature","-Ylog-classpath")

// run only those it tests, that are available for the selected backend
testOptions in IntegrationTest := Seq(
	Tests.Filter(s => itTests.contains(s)),
	Tests.Argument("-oDF")
)

sourcesInBase := false
EclipseKeys.skipParents in ThisBuild := false  // to enable piglet (parent not only children) eclispe import
