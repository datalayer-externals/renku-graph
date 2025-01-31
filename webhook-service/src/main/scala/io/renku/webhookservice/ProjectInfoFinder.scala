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

package io.renku.webhookservice

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.events.consumers.Project
import io.renku.graph.model.projects
import io.renku.http.client.{AccessToken, GitLabClient}
import io.renku.http.tinytypes.TinyTypeURIEncoder._
import org.http4s.Status.NotFound
import org.http4s.implicits._
import org.typelevel.log4cats.Logger

private trait ProjectInfoFinder[F[_]] {
  def findProjectInfo(projectId: projects.GitLabId)(implicit maybeAccessToken: Option[AccessToken]): F[Option[Project]]
}

private class ProjectInfoFinderImpl[F[_]: Async: GitLabClient: Logger] extends ProjectInfoFinder[F] {

  import io.circe._
  import io.renku.http.client.RestClientError.UnauthorizedException
  import io.renku.tinytypes.json.TinyTypeDecoders._
  import org.http4s.Status.{Ok, Unauthorized}
  import org.http4s._
  import org.http4s.circe.CirceEntityDecoder._

  def findProjectInfo(projectId: projects.GitLabId)(implicit mat: Option[AccessToken]): F[Option[Project]] =
    GitLabClient[F].get(uri"projects" / projectId, "single-project")(mapResponse)

  private lazy val mapResponse: PartialFunction[(Status, Request[F], Response[F]), F[Option[Project]]] = {
    case (Ok, _, response)    => response.as[Project].map(_.some)
    case (NotFound, _, _)     => Option.empty[Project].pure[F]
    case (Unauthorized, _, _) => MonadThrow[F].raiseError(UnauthorizedException)
  }

  private implicit lazy val projectDecoder: Decoder[Project] = cursor =>
    (cursor.downField("id").as[projects.GitLabId], cursor.downField("path_with_namespace").as[projects.Slug])
      .mapN(Project(_, _))
}

private object ProjectInfoFinder {
  def apply[F[_]: Async: GitLabClient: Logger]: F[ProjectInfoFinder[F]] =
    new ProjectInfoFinderImpl[F].pure[F].widen[ProjectInfoFinder[F]]
}
