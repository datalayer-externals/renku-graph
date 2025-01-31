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

package io.renku.graph.http.server.security

import cats.effect.Async
import cats.syntax.all._
import cats.{MonadThrow, Parallel}
import io.renku.graph.http.server.security.Authorizer.SecurityRecordFinder
import io.renku.graph.model.{RenkuUrl, projects}
import io.renku.http.client.GitLabClient
import io.renku.http.server.security.model.AuthUser
import io.renku.triplesstore.{ProjectSparqlClient, SparqlQueryTimeRecorder}
import org.typelevel.log4cats.Logger

object ProjectSlugRecordsFinder {

  def apply[F[_]: Async: Parallel: Logger: SparqlQueryTimeRecorder: GitLabClient](
      projectSparqlClient: ProjectSparqlClient[F],
      renkuUrl:            RenkuUrl
  ): F[SecurityRecordFinder[F, projects.Slug]] =
    (ProjectAuthRecordsFinder[F](projectSparqlClient, renkuUrl).pure[F] -> GLSlugRecordsFinder[F])
      .mapN(new ProjectSlugRecordsFinderImpl[F](_, _))
}

private class ProjectSlugRecordsFinderImpl[F[_]: MonadThrow](tsSlugRecordsFinder: ProjectAuthRecordsFinder[F],
                                                             glSlugRecordsFinder: GLSlugRecordsFinder[F]
) extends SecurityRecordFinder[F, projects.Slug] {

  override def apply(slug: projects.Slug, maybeAuthUser: Option[AuthUser]): F[List[Authorizer.SecurityRecord]] =
    tsSlugRecordsFinder(slug, maybeAuthUser) >>= {
      case Nil      => glSlugRecordsFinder(slug, maybeAuthUser)
      case nonEmpty => nonEmpty.pure[F]
    }
}
