// format: off
organization := "ch.datascience"
name         := "renku-graph"
scalaVersion := "2.12.8"

// This project contains nothing to package, like pure POM maven project
packagedArtifacts := Map.empty

releaseVersionBump := sbtrelease.Version.Bump.Minor
releaseIgnoreUntrackedFiles := true
releaseTagName := (version in ThisBuild).value.toString

lazy val root = Project(
  id   = "renku-graph",
  base = file(".")
).settings(
  skip in publish := true, 
  publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))
).aggregate(
  graphCommons,
  dbEventLog,
  tokenRepository,
  webhookService,
  triplesGenerator
)

lazy val graphCommons = Project(
  id   = "graph-commons",
  base = file("graph-commons")
).settings(
  commonSettings
).enablePlugins(
  AutomateHeaderPlugin
)

lazy val dbEventLog = Project(
  id   = "db-event-log",
  base = file("db-event-log")
).settings(
  commonSettings
).dependsOn(
  graphCommons % "compile->compile",
  graphCommons % "test->test"
).enablePlugins(
  AutomateHeaderPlugin
)

lazy val webhookService = Project(
  id   = "webhook-service",
  base = file("webhook-service")
).settings(
  commonSettings
).dependsOn(
  graphCommons % "compile->compile",
  graphCommons % "test->test",
  dbEventLog   % "compile->compile",
  dbEventLog   % "test->test"
).enablePlugins(
  JavaAppPackaging,
  AutomateHeaderPlugin
)

lazy val triplesGenerator = Project(
  id   = "triples-generator",
  base = file("triples-generator")
).settings(
  commonSettings
).dependsOn(
  graphCommons % "compile->compile",
  graphCommons % "test->test",
  dbEventLog   % "compile->compile",
  dbEventLog   % "test->test"
).enablePlugins(
  JavaAppPackaging,
  AutomateHeaderPlugin
)

lazy val tokenRepository = Project(
  id   = "token-repository",
  base = file("token-repository")
).settings(
  commonSettings
).dependsOn(
  graphCommons % "compile->compile",
  graphCommons % "test->test"
).enablePlugins(
  JavaAppPackaging,
  AutomateHeaderPlugin
)


lazy val acceptanceTests = Project(
  id   = "acceptance-tests",
  base = file("acceptance-tests")
).settings(
  commonSettings
).dependsOn(
  webhookService,
  triplesGenerator,
  tokenRepository,
  graphCommons % "test->test",
  dbEventLog   % "test->test"
).enablePlugins(
  AutomateHeaderPlugin
)

lazy val commonSettings = Seq(
  organization := "ch.datascience",
  scalaVersion := "2.12.8",

  skip in publish := true,
  publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo"))),
  
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Compile, packageSrc) := false,

  scalacOptions += "-Ypartial-unification",
  scalacOptions += "-feature",
  scalacOptions += "-unchecked",
  scalacOptions += "-deprecation",
  scalacOptions += "-Ywarn-value-discard",
  scalacOptions += "-Xfatal-warnings",
  
  organizationName := "Swiss Data Science Center (SDSC)",
  startYear := Some(java.time.LocalDate.now().getYear),
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  headerLicense := Some(HeaderLicense.Custom(
    s"""Copyright ${java.time.LocalDate.now().getYear} Swiss Data Science Center (SDSC)
|A partnership between École Polytechnique Fédérale de Lausanne (EPFL) and
|Eidgenössische Technische Hochschule Zürich (ETHZ).
|
|Licensed under the Apache License, Version 2.0 (the "License");
|you may not use this file except in compliance with the License.
|You may obtain a copy of the License at
|
|    http://www.apache.org/licenses/LICENSE-2.0
|
|Unless required by applicable law or agreed to in writing, software
|distributed under the License is distributed on an "AS IS" BASIS,
|WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
|See the License for the specific language governing permissions and
|limitations under the License.""".stripMargin
  ))
)
// format: on

import ReleaseTransformations._
import sbtrelease.Versions

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  setReleaseVersionToChart,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  setNextVersion,
  setNextVersionToChart,
  commitNextVersion,
  pushChanges
)

val setReleaseVersionToChart: ReleaseStep = setChartVersion(_._1)
val setNextVersionToChart:    ReleaseStep = setChartVersion(_._2)

private def setChartVersion(selectVersion: Versions => String): ReleaseStep = { state: State =>
  val versions = state.get(versions).getOrElse {
    sys.error("No versions are set! Was this release part executed before inquireVersions?")
  }

  val version = selectVersion(versions)
  updateChartVersion(state, version)

  state
}

val chartFile = baseDirectory.value / "helm-chart" / "renku-graph" / "Chart.yaml"

private def updateChartVersion(st: State, version: String): Unit = {
  val fileLines = IO.readLines(chartFile)
  val updatedLines = fileLines.map {
    case line if line.startsWith("version:") => s"version: $version"
    case line                                => line
  }
  IO.writeLines(chartFile, updatedLines)
}
