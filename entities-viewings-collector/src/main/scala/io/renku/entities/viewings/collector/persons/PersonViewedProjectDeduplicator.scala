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

package io.renku.entities.viewings.collector.persons

import cats.syntax.all._
import io.renku.graph.model.{persons, projects}
import io.renku.triplesstore.TSClient

private trait PersonViewedProjectDeduplicator[F[_]] {
  def deduplicate(personId: persons.ResourceId, projectId: projects.ResourceId): F[Unit]
}

private object PersonViewedProjectDeduplicator {
  def apply[F[_]](tsClient: TSClient[F]): PersonViewedProjectDeduplicator[F] =
    new PersonViewedProjectDeduplicatorImpl[F](tsClient)
}

private class PersonViewedProjectDeduplicatorImpl[F[_]](tsClient: TSClient[F])
    extends PersonViewedProjectDeduplicator[F] {

  import eu.timepit.refined.auto._
  import io.renku.graph.model.GraphClass
  import io.renku.graph.model.Schemas._
  import io.renku.jsonld.syntax._
  import io.renku.triplesstore.SparqlQuery
  import io.renku.triplesstore.SparqlQuery.Prefixes
  import io.renku.triplesstore.client.syntax._
  import tsClient.updateWithNoResult

  override def deduplicate(personId: persons.ResourceId, projectId: projects.ResourceId): F[Unit] = updateWithNoResult(
    SparqlQuery.ofUnsafe(
      show"${GraphClass.PersonViewings}: deduplicate project viewings",
      Prefixes of renku -> "renku",
      sparql"""|DELETE {
               |  GRAPH ${GraphClass.PersonViewings.id} { ?viewingId renku:dateViewed ?date }
               |}
               |WHERE {
               |  GRAPH ${GraphClass.PersonViewings.id} {
               |    BIND (${personId.asEntityId} AS ?personId)
               |    {
               |      SELECT ?viewingId (MAX(?date) AS ?maxDate)
               |      WHERE {
               |        ?personId renku:viewedProject ?viewingId.
               |        ?viewingId renku:project ${projectId.asEntityId};
               |                   renku:dateViewed ?date.
               |      }
               |      GROUP BY ?viewingId
               |      HAVING (COUNT(?date) > 1)
               |    }
               |    ?viewingId renku:dateViewed ?date.
               |    FILTER (?date != ?maxDate)
               |  }
               |}
               |""".stripMargin
    )
  )
}
