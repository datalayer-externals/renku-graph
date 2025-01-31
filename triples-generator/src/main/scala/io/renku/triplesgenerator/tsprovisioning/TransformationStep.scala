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

package io.renku.triplesgenerator.tsprovisioning

import TransformationStep.{ProjectWithQueries, Transformation}
import cats.Monoid
import cats.data.EitherT
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import io.renku.graph.model.entities.Project
import io.renku.triplesgenerator.errors.ProcessingRecoverableError
import io.renku.triplesstore.SparqlQuery

private final case class TransformationStep[F[_]](
    name:           String Refined NonEmpty,
    transformation: Transformation[F]
) {
  def run(project: Project): ProjectWithQueries[F] = transformation(project)
}

private object TransformationStep {

  type Transformation[F[_]]     = Project => ProjectWithQueries[F]
  type ProjectWithQueries[F[_]] = EitherT[F, ProcessingRecoverableError, (Project, Queries)]

  final case class Queries(preDataUploadQueries: List[SparqlQuery], postDataUploadQueries: List[SparqlQuery]) {
    def append(other: Queries): Queries =
      Queries(
        preDataUploadQueries ::: other.preDataUploadQueries,
        postDataUploadQueries ::: other.postDataUploadQueries
      )

    def ++(other: Queries): Queries =
      append(other)
  }

  object Queries {
    val empty: Queries = Queries(Nil, Nil)
    def preDataQueriesOnly(queries:  List[SparqlQuery]): Queries = Queries(queries, Nil)
    def postDataQueriesOnly(queries: List[SparqlQuery]): Queries = Queries(Nil, queries)

    implicit val queriesMonoid: Monoid[Queries] =
      Monoid.instance(empty, _ ++ _)
  }
}
