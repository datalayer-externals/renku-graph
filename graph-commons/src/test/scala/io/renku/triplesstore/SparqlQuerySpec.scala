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

import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.collection.NonEmpty
import io.renku.generators.CommonGraphGenerators._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.triplesstore.SparqlQuery.{Prefix, Prefixes}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import scala.util.{Failure, Success, Try}

class SparqlQuerySpec extends AnyWordSpec with should.Matchers {

  "of" should {

    "instantiate SparqlQuery using a set of Prefix objects" in {
      val name   = nonBlankStrings().generateOne
      val body   = sentences().generateOne.value
      val prefix = sparqlPrefixes.generateOne

      val sparql = SparqlQuery.of(name, Set(prefix), body)

      sparql.name               shouldBe name
      sparql.prefixes           shouldBe Set(prefix)
      sparql.body               shouldBe body
      sparql.maybePagingRequest shouldBe None
    }

    "instantiate SparqlQuery using the Prefixes.of set" in {
      val name              = nonBlankStrings().generateOne
      val body              = sentences().generateOne.value
      val prefixName        = nonBlankStrings().generateOne
      val prefixSchema      = schemas.generateOne
      val otherPrefixName   = nonBlankStrings().generateOne
      val otherPrefixSchema = schemas.generateDifferentThan(prefixSchema)

      val sparql = SparqlQuery.of(
        name,
        Prefixes.of(prefixSchema -> prefixName, otherPrefixSchema -> otherPrefixName),
        body
      )

      sparql.name     shouldBe name
      sparql.prefixes shouldBe Set(Prefix(prefixName, prefixSchema)) + Prefix(otherPrefixName, otherPrefixSchema)
      sparql.body     shouldBe body
      sparql.maybePagingRequest shouldBe None
    }
  }

  "toString" should {

    "render the body only if no prefixes and paging request given" in {
      val body = sentences().generateOne.value

      SparqlQuery(name = "test query", Set.empty, body).toString shouldBe body
    }

    "render the prefixes and body only if no paging request given" in {
      val prefix1 = nonBlankStrings().generateOne
      val prefix2 = nonBlankStrings().generateOne
      val body    = sentences().generateOne.value

      SparqlQuery(name = "test query", Set(prefix1, prefix2), body).toString should (
        equal(
          s"""|$prefix1
              |$prefix2
              |$body""".stripMargin
        ) or equal(
          s"""|$prefix2
              |$prefix1
              |$body""".stripMargin
        )
      )
    }

    "render the prefixes, body and paging request" in {
      val prefix = nonBlankStrings().generateOne
      val body =
        s"""|${sentences().generateOne.value}
            |ORDER BY ASC(?field)""".stripMargin
      val pagingRequest = pagingRequests.generateOne

      val Success(query) = SparqlQuery(name = "test query", Set(prefix), body).include[Try](pagingRequest)

      query.toString shouldBe
        s"""|$prefix
            |$body
            |LIMIT ${pagingRequest.perPage}
            |OFFSET ${(pagingRequest.page.value - 1) * pagingRequest.perPage.value}""".stripMargin
    }
  }

  "show" should {

    "return a String representation of the query containing its name and the toString" in {
      val name: String Refined NonEmpty = "test query"
      val prefix = nonBlankStrings().generateOne
      val body   = sentences().generateOne.value
      val query  = SparqlQuery(name, Set(prefix), body)

      query.show shouldBe s"$name:\n$query"
    }
  }

  "include" should {

    "successfully add the given paging request to the SparqlQuery if there's 'ORDER BY' clause in the body" in {
      val query = SparqlQuery(
        name = "test query",
        prefixes = Set.empty,
        body = s"""|${sentences().generateOne.value}
                   |ORDER BY ASC(?field)
                   |""".stripMargin
      )
      val pagingRequest = pagingRequests.generateOne

      query.include[Try](pagingRequest) shouldBe SparqlQuery(name = "test query",
                                                             query.prefixes,
                                                             query.body,
                                                             Some(pagingRequest)
      ).pure[Try]
    }

    "successfully add the given paging request to the SparqlQuery if there's 'order by' clause in the body" in {
      val query = SparqlQuery(
        name = "test query",
        prefixes = Set.empty,
        body = s"""|${sentences().generateOne.value}
                   |order by asc(?field)
                   |""".stripMargin
      )
      val pagingRequest = pagingRequests.generateOne

      query.include[Try](pagingRequest) shouldBe SparqlQuery(name = "test query",
                                                             query.prefixes,
                                                             query.body,
                                                             Some(pagingRequest)
      ).pure[Try]
    }

    "successfully add the given paging request to the SparqlQuery " +
      "if there's 'ORDER BY' clause with additional functions like 'lcase'" in {
        val query = SparqlQuery(
          name = "test query",
          prefixes = Set.empty,
          body = s"""|${sentences().generateOne.value}
                     |ORDER BY ASC(lcase(?field))
                     |""".stripMargin
        )
        val pagingRequest = pagingRequests.generateOne

        query.include[Try](pagingRequest) shouldBe SparqlQuery(name = "test query",
                                                               query.prefixes,
                                                               query.body,
                                                               Some(pagingRequest)
        ).pure[Try]
      }

    "successfully add the given paging request to the SparqlQuery if there's 'order by' clause in the body" +
      "with more than one sort variable" in {
        val query = SparqlQuery(
          name = "test query",
          prefixes = Set.empty,
          body = s"""|${sentences().generateOne.value}
                     |order by desc(?foo) asc(?field)
                     |""".stripMargin
        )
        val pagingRequest = pagingRequests.generateOne

        query.include[Try](pagingRequest) shouldBe SparqlQuery(name = "test query",
                                                               query.prefixes,
                                                               query.body,
                                                               Some(pagingRequest)
        ).pure[Try]
      }

    "fail adding the given paging request if there's no ORDER BY clause in the body" in {
      val query         = SparqlQuery(name = "test query", prefixes = Set.empty, body = sentences().generateOne.value)
      val pagingRequest = pagingRequests.generateOne

      val Failure(exception) = query.include[Try](pagingRequest)

      exception.getMessage shouldBe "Sparql query cannot be used for paging as there's no ending ORDER BY clause"
    }
  }

  "toCountQuery" should {

    "wrap query's body with the COUNT clause" in {
      val prefixes = Set(sparqlPrefixes.generateOne)
      val body     = sentences().generateOne.value
      val query    = SparqlQuery.of(name = "test query", prefixes, body)

      val actual = query.toCountQuery

      actual.body shouldBe
        s"""|SELECT (COUNT(*) AS ?${SparqlQuery.totalField})
            |WHERE {
            |  $body
            |}""".stripMargin
      actual.prefixes           shouldBe prefixes
      actual.maybePagingRequest shouldBe None
    }
  }
}
