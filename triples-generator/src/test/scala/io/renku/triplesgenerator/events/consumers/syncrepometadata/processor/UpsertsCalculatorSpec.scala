/*
 * Copyright 2023 Swiss Data Science Center (SDSC)
 * A partnership between École Polytechnique Fédérale de Lausanne (EPFL) and
 * Eidgenössische Technische Hochschule Zürich (ETHZ).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.renku.triplesgenerator.events.consumers.syncrepometadata.processor

import Generators._
import cats.effect.IO
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.entities.searchgraphs.SearchInfoDatasets
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.RenkuTinyTypeGenerators.projectNames
import io.renku.graph.model.testentities._
import io.renku.graph.model.{GraphClass, entities, projects}
import io.renku.interpreters.TestLogger
import io.renku.jsonld.syntax._
import io.renku.testtools.CustomAsyncIOSpec
import io.renku.triplesstore.SparqlQuery.Prefixes
import io.renku.triplesstore.client.syntax._
import io.renku.triplesstore.{InMemoryJenaForSpec, ProjectsDataset, SparqlQuery}
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should
import org.scalatest.{OptionValues, Succeeded}
import org.typelevel.log4cats.Logger

class UpsertsCalculatorSpec
    extends AsyncFlatSpec
    with CustomAsyncIOSpec
    with should.Matchers
    with OptionValues
    with InMemoryJenaForSpec
    with ProjectsDataset
    with SearchInfoDatasets
    with AsyncMockFactory {

  private lazy val newValueCalculator = mock[NewValueCalculator]
  private lazy val upsertsCalculator  = new UpsertsCalculatorImpl(newValueCalculator)

  it should "create upsert queries where there's a new name" in {

    val project = anyProjectEntities.generateOne.to[entities.Project]

    val tsData           = tsDataFrom(project)
    val glData           = glDataExtracts(project.path).generateOne
    val maybePayloadData = payloadDataExtracts(project.path).generateOption

    val newName = projectNames.generateOne
    givenNewValuesFinding(tsData,
                          glData,
                          maybePayloadData,
                          returning = newValuesFrom(tsData).copy(maybeName = newName.some)
    )
    val updatedTsData = tsData.copy(name = newName)

    for {
      _ <- provisionProject(project).assertNoException

      _ <- dataInProjectGraph(project).asserting(_.value shouldBe tsData)
      _ <- dataInProjectsGraph(project).asserting(_.value shouldBe tsData)

      _ <- execute(upsertsCalculator.calculateUpserts(tsData, glData, maybePayloadData)).assertNoException

      _ <- dataInProjectGraph(project).asserting(_.value shouldBe updatedTsData)
      _ <- dataInProjectsGraph(project).asserting(_.value shouldBe updatedTsData)
    } yield Succeeded
  }

  it should "create no upsert queries where there are no new values" in {

    val project = anyProjectEntities.generateOne.to[entities.Project]

    val tsData           = tsDataFrom(project)
    val glData           = glDataExtracts(project.path).generateOne
    val maybePayloadData = payloadDataExtracts(project.path).generateOption

    givenNewValuesFinding(tsData, glData, maybePayloadData, returning = newValuesFrom(tsData))

    for {
      _ <- provisionProject(project).assertNoException

      _ <- execute(upsertsCalculator.calculateUpserts(tsData, glData, maybePayloadData)).assertNoException

      _ <- dataInProjectGraph(project).asserting(_.value shouldBe tsData)
      _ <- dataInProjectsGraph(project).asserting(_.value shouldBe tsData)
    } yield Succeeded
  }

  private def execute(queries: List[SparqlQuery]) =
    queries.traverse_(runUpdate(on = projectsDataset, _))

  private def dataInProjectGraph(project: entities.Project): IO[Option[DataExtract.TS]] =
    runSelect(
      on = projectsDataset,
      SparqlQuery.ofUnsafe(
        "UpsertsCalculator Project fetch",
        Prefixes of (renku -> "renku", schema -> "schema"),
        sparql"""|SELECT ?id ?path ?name
                 |WHERE {
                 |  BIND (${GraphClass.Project.id(project.resourceId)} AS ?id)
                 |  GRAPH ?id {
                 |    ?id renku:projectPath ?path;
                 |        schema:name ?name
                 |  }
                 |}""".stripMargin
      )
    ).map(toDataExtract).flatMap(toOptionOrFail)

  private def dataInProjectsGraph(project: entities.Project): IO[Option[DataExtract.TS]] =
    runSelect(
      on = projectsDataset,
      SparqlQuery.ofUnsafe(
        "UpsertsCalculator Projects fetch",
        Prefixes of (renku -> "renku", schema -> "schema"),
        sparql"""|SELECT ?id ?path ?name
                 |WHERE {
                 |  BIND (${project.resourceId.asEntityId} AS ?id)
                 |  GRAPH ${GraphClass.Projects.id} {
                 |    ?id renku:projectPath ?path;
                 |        schema:name ?name
                 |  }
                 |}""".stripMargin
      )
    ).map(toDataExtract).flatMap(toOptionOrFail)

  private lazy val toDataExtract: List[Map[String, String]] => List[DataExtract.TS] =
    _.flatMap(row =>
      (row.get("id").map(projects.ResourceId), row.get("path").map(projects.Path), row.get("name").map(projects.Name))
        .mapN(DataExtract.TS)
    )

  private def givenNewValuesFinding(tsData:           DataExtract.TS,
                                    glData:           DataExtract.GL,
                                    maybePayloadData: Option[DataExtract.Payload],
                                    returning:        NewValues
  ) = (newValueCalculator.findNewValues _)
    .expects(tsData, glData, maybePayloadData)
    .returning(returning)

  private lazy val toOptionOrFail: List[DataExtract.TS] => IO[Option[DataExtract.TS]] = {
    case Nil      => Option.empty[DataExtract.TS].pure[IO]
    case h :: Nil => h.some.pure[IO]
    case _        => new Exception("Found more than one row").raiseError[IO, Nothing]
  }

  implicit override val ioLogger: Logger[IO] = TestLogger[IO]()
}
