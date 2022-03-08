/*
 * Copyright 2022 Swiss Data Science Center (SDSC)
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

package io.renku.tokenrepository.repository.fetching

import cats.MonadThrow
import cats.data.OptionT
import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.circe.syntax._
import io.renku.db.SessionResource
import io.renku.graph.model.projects
import io.renku.http.ErrorMessage._
import io.renku.http.client.AccessToken
import io.renku.http.{ErrorMessage, InfoMessage}
import io.renku.metrics.LabeledHistogram
import io.renku.tokenrepository.repository.ProjectsTokensDB
import org.http4s.Response
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.Logger

import scala.util.control.NonFatal

trait FetchTokenEndpoint[F[_]] {
  def fetchToken[ID](
      projectIdentifier: ID
  )(implicit findToken:  ID => OptionT[F, AccessToken]): F[Response[F]]

  implicit val findById:   projects.Id => OptionT[F, AccessToken]
  implicit val findByPath: projects.Path => OptionT[F, AccessToken]
}

class FetchTokenEndpointImpl[F[_]: MonadThrow: Logger](tokenFinder: TokenFinder[F])
    extends Http4sDsl[F]
    with FetchTokenEndpoint[F] {

  override def fetchToken[ID](
      projectIdentifier: ID
  )(implicit findToken:  ID => OptionT[F, AccessToken]): F[Response[F]] =
    findToken(projectIdentifier).value
      .flatMap(toHttpResult(projectIdentifier))
      .recoverWith(httpResult(projectIdentifier))

  private def toHttpResult[ID](
      projectIdentifier: ID
  ): Option[AccessToken] => F[Response[F]] = {
    case Some(token) => Ok(token.asJson)
    case None        => NotFound(InfoMessage(s"Token for project: $projectIdentifier not found"))
  }

  private def httpResult[ID](
      projectIdentifier: ID
  ): PartialFunction[Throwable, F[Response[F]]] = { case NonFatal(exception) =>
    val errorMessage = ErrorMessage(s"Finding token for project: $projectIdentifier failed")
    Logger[F].error(exception)(errorMessage.value)
    InternalServerError(errorMessage)
  }

  implicit val findById:   projects.Id => OptionT[F, AccessToken]   = tokenFinder.findToken
  implicit val findByPath: projects.Path => OptionT[F, AccessToken] = tokenFinder.findToken
}

object FetchTokenEndpoint {
  def apply[F[_]: MonadCancelThrow: Logger](
      sessionResource:  SessionResource[F, ProjectsTokensDB],
      queriesExecTimes: LabeledHistogram[F]
  ): F[FetchTokenEndpoint[F]] = for {
    tokenFinder <- TokenFinder(sessionResource, queriesExecTimes)
  } yield new FetchTokenEndpointImpl[F](tokenFinder)
}
