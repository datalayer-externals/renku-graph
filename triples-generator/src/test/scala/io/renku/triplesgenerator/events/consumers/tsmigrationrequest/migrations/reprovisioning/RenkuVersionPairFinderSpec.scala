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

package io.renku.triplesgenerator.events.consumers.tsmigrationrequest.migrations.reprovisioning

import cats.effect.IO
import cats.syntax.all._
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.RenkuUrl
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Warn
import io.renku.logging.TestExecutionTimeRecorder
import io.renku.testtools.IOSpec
import io.renku.triplesgenerator.generators.VersionGenerators.renkuVersionPairs
import io.renku.triplesstore.{InMemoryJenaForSpec, MigrationsDataset, SparqlQueryTimeRecorder}
import org.scalatest._
import matchers._
import org.scalatest.wordspec.AnyWordSpec

class RenkuVersionPairFinderSpec
    extends AnyWordSpec
    with IOSpec
    with should.Matchers
    with InMemoryJenaForSpec
    with MigrationsDataset {

  private implicit lazy val renkuUrl: RenkuUrl = renkuUrls.generateOne

  "find" should {

    "return the Version pair in the triples store" in new TestCase {

      upload(to = migrationsDataset, currentVersionPair)

      versionPairFinder.find().unsafeRunSync() shouldBe currentVersionPair.some

      logger.loggedOnly(Warn(s"re-provisioning - version pair find finished${executionTimeRecorder.executionTimeInfo}"))
    }

    "return None if there are no Version pair in the triple store" in new TestCase {

      versionPairFinder.find().unsafeRunSync() shouldBe None

      logger.loggedOnly(Warn(s"re-provisioning - version pair find finished${executionTimeRecorder.executionTimeInfo}"))
    }

    "return an IllegalStateException if there are multiple Version Pairs" in new TestCase {

      upload(to = migrationsDataset, currentVersionPair, renkuVersionPairs.generateOne)

      intercept[IllegalStateException] {
        versionPairFinder.find().unsafeRunSync()
      }.getMessage should startWith("Too many Version pairs found:")
    }
  }

  private trait TestCase {
    val currentVersionPair = renkuVersionPairs.generateOne

    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val executionTimeRecorder = TestExecutionTimeRecorder[IO]()
    private implicit val timeRecorder: SparqlQueryTimeRecorder[IO] =
      new SparqlQueryTimeRecorder[IO](executionTimeRecorder)
    val versionPairFinder = new RenkuVersionPairFinderImpl[IO](migrationsDSConnectionInfo)
  }
}
