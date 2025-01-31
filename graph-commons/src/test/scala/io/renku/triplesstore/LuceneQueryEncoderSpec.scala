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

package io.renku.triplesstore

import eu.timepit.refined.auto._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.{NonBlank, nonEmptyStrings}
import io.renku.generators.jsonld.JsonLDGenerators.entityIds
import io.renku.graph.model.testentities.{schema, _}
import io.renku.testtools.IOSpec
import io.renku.triplesstore.SparqlQuery.Prefixes
import io.renku.triplesstore.client.model.Quad
import io.renku.triplesstore.client.sparql.LuceneQuery
import io.renku.triplesstore.client.syntax._
import org.scalacheck.Gen
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class LuceneQueryEncoderSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryJenaForSpec
    with ProjectsDataset
    with should.Matchers {

  private val specialChars =
    List[NonBlank]("\\", "+", "-", "&", "|", "!", "(", ")", "{", "}", "[", "]", "^", "\"", "~", "*", "?", ":", "/")

  "queryAsString" should {

    specialChars foreach { specialChar =>
      s"escape '$specialChar' so it can be used as a string in the Lucene search" in {

        val name = s"$specialChar${nonEmptyStrings(minLength = 3).generateOne}"
        val quad = Quad(entityIds.generateOne, entityIds.generateOne, schema / "name", name.asTripleObject)

        insert(to = projectsDataset, quad)

        runSelect(
          on = projectsDataset,
          SparqlQuery.of(
            "lucene test query",
            Prefixes of (schema -> "schema", text -> "text"),
            sparql"""|SELECT ?name
                     |WHERE {
                     |  GRAPH ?g {
                     |    ?id text:query (schema:name ${LuceneQuery.escape(name).asTripleObject});
                     |    schema:name ?name
                     |  }
                     |}""".stripMargin
          )
        ).unsafeRunSync() shouldBe List(Map("name" -> name.value))
      }
    }
  }

  "tripleObjectEncoder" should {

    "encode the query as triple object" in {

      val name = s"${specialCharsGen.generateOne}${nonEmptyStrings(minLength = 3).generateOne}"
      val quad = Quad(entityIds.generateOne, entityIds.generateOne, schema / "name", name.asTripleObject)

      insert(to = projectsDataset, quad)

      runSelect(
        on = projectsDataset,
        SparqlQuery.of(
          "lucene test query",
          Prefixes of (schema -> "schema", text -> "text"),
          sparql"""|SELECT ?name
                   |WHERE {
                   |  GRAPH ?g {
                   |    ?id text:query (schema:name ${LuceneQuery.fuzzy(name).asTripleObject});
                   |    schema:name ?name
                   |  }
                   |}""".stripMargin
        )
      ).unsafeRunSync() shouldBe List(Map("name" -> name.value))
    }
  }

  private lazy val specialCharsGen: Gen[NonBlank] = Gen.oneOf(specialChars)
}
