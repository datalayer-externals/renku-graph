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

package io.renku.triplesgenerator.events.consumers.tsmigrationrequest.migrations.tooling

import cats.effect.Async
import cats.syntax.all._
import io.circe.Decoder
import io.renku.graph.model.projects
import io.renku.triplesstore.ResultsDecoder._
import io.renku.triplesstore._
import org.typelevel.log4cats.Logger

private trait ProjectsFinder[F[_]] {
  def findProjects: F[List[projects.Slug]]
}

private object ProjectsFinder {
  def apply[F[_]: Async: Logger: SparqlQueryTimeRecorder](query: SparqlQuery): F[ProjectsFinder[F]] =
    ProjectsConnectionConfig[F]().map(new ProjectsFinderImpl(query, _))
}

private class ProjectsFinderImpl[F[_]: Async: Logger: SparqlQueryTimeRecorder](
    query:       SparqlQuery,
    storeConfig: ProjectsConnectionConfig
) extends TSClientImpl[F](storeConfig)
    with ProjectsFinder[F] {

  override def findProjects: F[List[projects.Slug]] = queryExpecting[List[projects.Slug]](query)

  private implicit lazy val slugsDecoder: Decoder[List[projects.Slug]] = ResultsDecoder[List, projects.Slug] {
    implicit cursor =>
      import io.renku.tinytypes.json.TinyTypeDecoders._
      extract[projects.Slug]("slug")
  }
}
