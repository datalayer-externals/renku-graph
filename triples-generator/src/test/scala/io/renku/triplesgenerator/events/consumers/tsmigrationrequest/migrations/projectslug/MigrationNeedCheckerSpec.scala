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

package io.renku.triplesgenerator.events.consumers.tsmigrationrequest
package migrations
package projectslug

import cats.effect.IO
import io.renku.entities.searchgraphs.SearchInfoDatasets
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.testentities._
import io.renku.interpreters.TestLogger
import io.renku.logging.TestSparqlQueryTimeRecorder
import io.renku.testtools.CustomAsyncIOSpec
import io.renku.triplesstore._
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should
import org.typelevel.log4cats.Logger

class MigrationNeedCheckerSpec
    extends AsyncFlatSpec
    with CustomAsyncIOSpec
    with should.Matchers
    with InMemoryJenaForSpec
    with ProjectsDataset
    with SearchInfoDatasets
    with AsyncMockFactory
    with TSTooling {

  it should "return Yes if there are projects without renku:slug in the Projects or Project graph" in {

    val project = anyProjectEntities.generateOne

    provisionTestProject(project).assertNoException >>
      deleteRenkuSlugProp(project.resourceId).assertNoException >>
      checker.checkMigrationNeeded.asserting(_ shouldBe a[ConditionedMigration.MigrationRequired.Yes])
  }

  it should "return Yes if there are projects without renku:slug in the Project graph" in {

    val project = anyProjectEntities.generateOne

    provisionTestProject(project).assertNoException >>
      deleteProjectRenkuSlug(project.resourceId).assertNoException >>
      checker.checkMigrationNeeded.asserting(_ shouldBe a[ConditionedMigration.MigrationRequired.Yes])
  }

  it should "return Yes if there are projects without renku:slug in the Projects graph" in {

    val project = anyProjectEntities.generateOne

    provisionTestProject(project).assertNoException >>
      deleteProjectsRenkuSlug(project.resourceId).assertNoException >>
      checker.checkMigrationNeeded.asserting(_ shouldBe a[ConditionedMigration.MigrationRequired.Yes])
  }

  it should "return No if there are no projects without renku:slug" in {

    val project = anyProjectEntities.generateOne

    provisionTestProject(project).assertNoException >>
      checker.checkMigrationNeeded.asserting(_ shouldBe a[ConditionedMigration.MigrationRequired.No])
  }

  implicit override lazy val ioLogger: Logger[IO]                  = TestLogger[IO]()
  private implicit val timeRecorder:   SparqlQueryTimeRecorder[IO] = TestSparqlQueryTimeRecorder[IO].unsafeRunSync()
  private lazy val checker = new MigrationNeedCheckerImpl[IO](TSClient[IO](projectsDSConnectionInfo))
}
