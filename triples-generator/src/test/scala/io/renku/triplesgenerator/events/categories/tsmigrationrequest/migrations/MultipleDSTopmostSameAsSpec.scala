/*
 * Copyright 2022 Swiss Data Science Center (SDSC)
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

package io.renku.triplesgenerator.events.categories.tsmigrationrequest.migrations

import cats.effect.IO
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators.datasetResourceIds
import io.renku.graph.model.datasets
import io.renku.graph.model.datasets.TopmostSameAs
import io.renku.graph.model.testentities._
import io.renku.graph.model.views.RdfResource
import io.renku.interpreters.TestLogger
import io.renku.logging.TestSparqlQueryTimeRecorder
import io.renku.metrics.MetricsRegistry
import io.renku.rdfstore.{InMemoryRdfStore, SparqlQueryTimeRecorder}
import io.renku.testtools.IOSpec
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import tooling.UpdateQueryMigration

class MultipleDSTopmostSameAsSpec extends AnyWordSpec with should.Matchers with IOSpec with InMemoryRdfStore {

  "query" should {

    "find all datasets having multiple topmostSameAs " +
      "while one of them points to the correct parent dataset (sameAs/url == topmostSameAs) " +
      "and remove all the excessive ones" in {
        val (originalDS, originalDSProject) = renkuProjectEntities(anyVisibility)
          .addDataset(datasetEntities(provenanceInternal))
          .generateOne

        val (importedDS1, importedDS1Project) =
          renkuProjectEntities(anyVisibility).importDataset(originalDS).generateOne

        val (importedDS2, importedDS2Project) =
          renkuProjectEntities(anyVisibility).importDataset(originalDS).generateOne

        loadToStore(originalDSProject, importedDS1Project, importedDS2Project)

        insertTriple(importedDS1.entityId, "renku:topmostSameAs", datasetResourceIds.generateOne.showAs[RdfResource])

        findTopmostSameAs(originalDS.identification.identifier)       shouldBe Set(TopmostSameAs(originalDS.entityId))
        findTopmostSameAs(importedDS1.identification.identifier).size shouldBe 2
        findTopmostSameAs(importedDS2.identification.identifier)      shouldBe Set(TopmostSameAs(originalDS.entityId))

        runUpdate(MultipleDSTopmostSameAs.query).unsafeRunSync() shouldBe ()

        findTopmostSameAs(originalDS.identification.identifier)  shouldBe Set(TopmostSameAs(originalDS.entityId))
        findTopmostSameAs(importedDS1.identification.identifier) shouldBe Set(TopmostSameAs(originalDS.entityId))
        findTopmostSameAs(importedDS2.identification.identifier) shouldBe Set(TopmostSameAs(originalDS.entityId))
      }
  }

  "apply" should {
    "return an QueryBasedMigration" in {
      implicit val logger:          TestLogger[IO]              = TestLogger[IO]()
      implicit val timeRecorder:    SparqlQueryTimeRecorder[IO] = TestSparqlQueryTimeRecorder[IO]
      implicit val metricsRegistry: MetricsRegistry[IO]         = new MetricsRegistry.DisabledMetricsRegistry[IO]()
      MultipleDSTopmostSameAs[IO].unsafeRunSync().getClass shouldBe classOf[UpdateQueryMigration[IO]]
    }
  }

  private def findTopmostSameAs(id: datasets.Identifier): Set[datasets.TopmostSameAs] =
    runQuery(s"""|SELECT ?sameAs 
                 |WHERE { 
                 |  ?id a schema:Dataset;
                 |  schema:identifier '$id';
                 |  renku:topmostSameAs ?sameAs
                 |}""".stripMargin)
      .unsafeRunSync()
      .map(row => datasets.TopmostSameAs(row("sameAs")))
      .toSet
}
