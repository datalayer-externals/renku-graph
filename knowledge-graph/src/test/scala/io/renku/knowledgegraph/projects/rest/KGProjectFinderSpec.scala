/*
 * Copyright 2021 Swiss Data Science Center (SDSC)
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

package io.renku.knowledgegraph.projects.rest

import cats.effect.IO
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.testentities._
import io.renku.interpreters.TestLogger
import io.renku.knowledgegraph.projects.rest.Converters._
import io.renku.knowledgegraph.projects.rest.KGProjectFinder.KGProject
import io.renku.logging.TestExecutionTimeRecorder
import io.renku.rdfstore.{InMemoryRdfStore, SparqlQueryTimeRecorder}
import io.renku.testtools.IOSpec
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class KGProjectFinderSpec
    extends AnyWordSpec
    with InMemoryRdfStore
    with ScalaCheckPropertyChecks
    with should.Matchers
    with IOSpec {

  "findProject" should {

    "return details of the project with the given path when there's no parent" in new TestCase {
      forAll(projectEntities(anyVisibility)) { project =>
        loadToStore(anyProjectEntities.generateOne, project)

        metadataFinder.findProject(project.path).unsafeRunSync() shouldBe Some(project.to[KGProject])
      }
    }

    "return details of the project with the given path if it has a parent project" in new TestCase {
      forAll(projectWithParentEntities(anyVisibility)) { project =>
        loadToStore(project, project.parent)

        metadataFinder.findProject(project.path).unsafeRunSync() shouldBe Some(project.to[KGProject])
      }
    }

    "return None if there's no project with the given path" in new TestCase {
      metadataFinder.findProject(projectPaths.generateOne).unsafeRunSync() shouldBe None
    }
  }

  private trait TestCase {
    private implicit val logger: TestLogger[IO] = TestLogger[IO]()
    private val timeRecorder = new SparqlQueryTimeRecorder[IO](TestExecutionTimeRecorder[IO]())
    val metadataFinder       = new KGProjectFinderImpl[IO](rdfStoreConfig, renkuBaseUrl, timeRecorder)
  }
}
