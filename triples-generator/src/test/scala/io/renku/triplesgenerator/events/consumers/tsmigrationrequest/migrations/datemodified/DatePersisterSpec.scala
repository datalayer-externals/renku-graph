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

package io.renku.triplesgenerator.events.consumers.tsmigrationrequest.migrations
package datemodified

import cats.effect.IO
import eu.timepit.refined.auto._
import io.renku.entities.searchgraphs.SearchInfoDatasets
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.testentities._
import io.renku.graph.model.{GraphClass, entities, projects}
import io.renku.interpreters.TestLogger
import io.renku.jsonld.syntax._
import io.renku.logging.TestSparqlQueryTimeRecorder
import io.renku.testtools.CustomAsyncIOSpec
import io.renku.triplesgenerator.events.consumers.tsmigrationrequest.ConditionedMigration
import io.renku.triplesstore.SparqlQuery.Prefixes
import io.renku.triplesstore._
import io.renku.triplesstore.client.syntax._
import org.scalatest.OptionValues
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should
import org.typelevel.log4cats.Logger

import java.time.Instant

class DatePersisterSpec
    extends AsyncFlatSpec
    with CustomAsyncIOSpec
    with should.Matchers
    with OptionValues
    with InMemoryJenaForSpec
    with ProjectsDataset
    with SearchInfoDatasets
    with TSTooling {

  it should "persist given dateCreated as dateModified" in {

    val project      = anyProjectEntities.generateOne.to[entities.Project]
    val expectedDate = projects.DateModified(project.dateCreated.value)

    provisionProject(project).assertNoException >>
      deleteModifiedDates(project.resourceId).assertNoException >>
      findProjectDateModified(project.resourceId).asserting(_ shouldBe None) >>
      findProjectsDateModified(project.resourceId).asserting(_ shouldBe None) >>
      persister
        .persistDateModified(ProjectInfo(project.resourceId, project.slug, project.dateCreated))
        .assertNoException >>
      findProjectDateModified(project.resourceId).asserting(_.value shouldBe expectedDate) >>
      findProjectsDateModified(project.resourceId).asserting(_.value shouldBe expectedDate) >>
      migrationNeedChecker.checkMigrationNeeded.asserting(_ shouldBe a[ConditionedMigration.MigrationRequired.No])
  }

  it should "leave the old dateModified if exists" in {

    val project = anyProjectEntities.generateOne.to[entities.Project]

    provisionProject(project).assertNoException >>
      findProjectDateModified(project.resourceId).asserting(_.value shouldBe project.dateModified) >>
      findProjectsDateModified(project.resourceId).asserting(_.value shouldBe project.dateModified) >>
      persister
        .persistDateModified(ProjectInfo(project.resourceId, project.slug, project.dateCreated))
        .assertNoException >>
      findProjectDateModified(project.resourceId).asserting(_.value shouldBe project.dateModified) >>
      findProjectsDateModified(project.resourceId).asserting(_.value shouldBe project.dateModified) >>
      migrationNeedChecker.checkMigrationNeeded.asserting(_ shouldBe a[ConditionedMigration.MigrationRequired.No])
  }

  implicit override lazy val ioLogger: Logger[IO]                  = TestLogger[IO]()
  private implicit val timeRecorder:   SparqlQueryTimeRecorder[IO] = TestSparqlQueryTimeRecorder[IO].unsafeRunSync()
  private lazy val tsClient             = TSClient[IO](projectsDSConnectionInfo)
  private lazy val persister            = new DatePersisterImpl[IO](tsClient)
  private lazy val migrationNeedChecker = new MigrationNeedCheckerImpl[IO](tsClient)

  private def findProjectDateModified(id: projects.ResourceId): IO[Option[projects.DateModified]] =
    runSelect(
      on = projectsDataset,
      SparqlQuery.ofUnsafe(
        "test find dateModified",
        Prefixes of schema -> "schema",
        sparql"""|SELECT ?date
                 |WHERE {
                 |  BIND (${id.asEntityId} AS ?id)
                 |  GRAPH ?id { ?id schema:dateModified ?date }
                 |}
                 |LIMIT 1
                 |""".stripMargin
      )
    ).map(_.map(_.get("date").map(Instant.parse).map(projects.DateModified(_))).headOption.flatten)

  private def findProjectsDateModified(id: projects.ResourceId): IO[Option[projects.DateModified]] =
    runSelect(
      on = projectsDataset,
      SparqlQuery.ofUnsafe(
        "test find dateModified",
        Prefixes of schema -> "schema",
        sparql"""|SELECT ?date
                 |WHERE {
                 |  BIND (${id.asEntityId} AS ?id)
                 |  GRAPH ${GraphClass.Projects.id} { ?id schema:dateModified ?date }
                 |}
                 |LIMIT 1
                 |""".stripMargin
      )
    ).map(_.map(_.get("date").map(Instant.parse).map(projects.DateModified(_))).headOption.flatten)
}
