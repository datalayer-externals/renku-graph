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

package io.renku.triplesstore.client.sparql

import cats.syntax.all._
import io.renku.triplesstore.client.model.TripleObjectEncoder
import io.renku.triplesstore.client.syntax._
import org.apache.lucene.queryparser.flexible.standard.QueryParserUtil

final class LuceneQuery(val query: String) extends AnyVal {
  def isQueryAll: Boolean = query == LuceneQuery.queryAll.query
}

object LuceneQuery {
  val queryAll: LuceneQuery = LuceneQuery("*")

  def apply(str: String): LuceneQuery = new LuceneQuery(str)

  def escape(str: String): LuceneQuery = LuceneQuery(LuceneQueryEncoder.queryAsString(str))

  def fuzzy(str: String): LuceneQuery =
    new LuceneQuery(
      QueryTokenizer.default
        .split(str)
        .map(_.trim)
        .map(QueryParserUtil.escape)
        .map(word => s"$word~")
        .mkString(" ")
    )

  implicit val tripleObjectEncoder: TripleObjectEncoder[LuceneQuery] =
    TripleObjectEncoder[String].contramap(_.query)
}
