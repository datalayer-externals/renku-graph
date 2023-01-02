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

package io.renku.knowledgegraph
package graphql

import cats.effect.IO
import io.circe.Json
import io.circe.literal._
import io.renku.generators.CommonGraphGenerators.authUsers
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.projects.Path
import io.renku.http.server.security.model.AuthUser
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import projects.files.lineage
import projects.files.lineage.LineageFinder
import projects.files.lineage.LineageGenerators._
import projects.files.lineage.model.Node.Location
import projects.files.lineage.model._
import sangria.ast.Document
import sangria.execution.Executor
import sangria.macros._
import sangria.marshalling.circe._

import scala.language.reflectiveCalls

class QuerySchemaSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with MockFactory
    with ScalaFutures
    with IntegrationPatience
    with should.Matchers
    with IOSpec {

  "query" should {

    "allow to search for lineage of a given projectPath, commitId and file" in new LineageTestCase {
      val query =
        graphql"""
        {
          lineage(projectPath: "namespace/project", filePath: "directory/file") {
            nodes {
              id
              location
              label
              type
            }
            edges {
              source
              target
            }
          }
        }"""

      givenFindLineage(Path("namespace/project"), Location("directory/file"))
        .returning(IO.pure(Some(lineage)))

      execute(query) shouldBe json(lineage)
    }
  }

  private trait TestCase {
    val lineageFinder = mock[LineageFinder[IO]]

    import scala.concurrent.ExecutionContext.Implicits.global
    val maybeAuthUser = authUsers.generateOption
    def execute(query: Document): Json =
      Executor
        .execute(
          QuerySchema[IO](lineage.graphql.QueryFields()),
          query,
          new LineageQueryContext[IO](lineageFinder, maybeAuthUser)
        )
        .futureValue
  }

  private trait LineageTestCase extends TestCase {

    def givenFindLineage(projectPath: Path, location: Location) = new {
      def returning(result: IO[Option[Lineage]]) =
        (lineageFinder
          .find(_: Path, _: Location, _: Option[AuthUser]))
          .expects(projectPath, location, maybeAuthUser)
          .returning(result)
    }

    private val sourceNode = entityNodes.generateOne
    private val targetNode = processRunNodes.generateOne
    lazy val lineage = Lineage(
      edges = Set(Edge(sourceNode.location, targetNode.location)),
      nodes = Set(sourceNode, targetNode)
    )

    def json(lineage: Lineage) = json"""{
      "data": {
        "lineage": {
          "nodes": ${Json.arr(lineage.nodes.map(toJson).toList: _*)},
          "edges": ${Json.arr(lineage.edges.map(toJson).toList: _*)}
        }
      }
    }"""

    private def toJson(node: Node) = json"""{
      "id": ${node.location.value},
      "location": ${node.location.value},
      "label": ${node.label.value},
      "type": ${node.typ.value}
    }"""

    private def toJson(edge: Edge) = json"""{
      "source" : ${edge.source.value},
      "target" : ${edge.target.value}
    }"""
  }
}
