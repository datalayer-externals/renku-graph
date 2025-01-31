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

package io.renku.webhookservice.webhookevents

import cats.data.NonEmptyList
import cats.effect._
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.circe.Decoder
import io.renku.data.Message
import io.renku.eventlog
import io.renku.eventlog.api.events.CommitSyncRequest
import io.renku.events.consumers.Project
import io.renku.graph.model.events.CommitId
import io.renku.graph.model.projects.{GitLabId, Slug}
import io.renku.http.client.RestClientError.UnauthorizedException
import io.renku.metrics.MetricsRegistry
import io.renku.webhookservice.crypto.HookTokenCrypto
import io.renku.webhookservice.crypto.HookTokenCrypto.SerializedHookToken
import io.renku.webhookservice.model.HookToken
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci._
import org.typelevel.log4cats.Logger

import scala.util.control.NonFatal

trait Endpoint[F[_]] {
  def processPushEvent(request: Request[F]): F[Response[F]]
}

class EndpointImpl[F[_]: Concurrent: Logger](
    hookTokenCrypto: HookTokenCrypto[F],
    elClient:        eventlog.api.events.Client[F]
) extends Http4sDsl[F]
    with Endpoint[F] {

  import Endpoint._
  import hookTokenCrypto._

  def processPushEvent(request: Request[F]): F[Response[F]] = {
    for {
      pushEvent @ (_, commitSyncRequest) <- request.as[(CommitId, CommitSyncRequest)] recoverWith badRequest
      authToken                          <- findHookToken(request)
      hookToken                          <- decrypt(authToken) recoverWith unauthorizedException
      _                                  <- validate(hookToken, commitSyncRequest)
      _                                  <- Spawn[F].start(elClient.send(commitSyncRequest))
      _                                  <- logInfo(pushEvent)
      response                           <- Accepted(Message.Info("Event accepted"))
    } yield response
  } recoverWith httpResponse

  private implicit lazy val startCommitEntityDecoder: EntityDecoder[F, (CommitId, CommitSyncRequest)] =
    jsonOf[F, (CommitId, CommitSyncRequest)]

  private lazy val badRequest: PartialFunction[Throwable, F[(CommitId, CommitSyncRequest)]] = {
    case NonFatal(exception) => BadRequestError(exception).raiseError[F, (CommitId, CommitSyncRequest)]
  }

  private case class BadRequestError(cause: Throwable) extends Exception(cause)

  private def findHookToken(request: Request[F]): F[SerializedHookToken] =
    request.headers.get(ci"X-Gitlab-Token") match {
      case None => UnauthorizedException.raiseError[F, SerializedHookToken]
      case Some(NonEmptyList(rawToken, _)) =>
        SerializedHookToken
          .from(rawToken.value)
          .fold(
            _ => UnauthorizedException.raiseError[F, SerializedHookToken],
            _.pure[F]
          )
    }

  private lazy val unauthorizedException: PartialFunction[Throwable, F[HookToken]] = { case NonFatal(_) =>
    UnauthorizedException.raiseError[F, HookToken]
  }

  private def validate(hookToken: HookToken, pushEvent: CommitSyncRequest): F[Unit] =
    if (hookToken.projectId == pushEvent.project.id) ().pure[F]
    else UnauthorizedException.raiseError[F, Unit]

  private lazy val httpResponse: PartialFunction[Throwable, F[Response[F]]] = {
    case BadRequestError(exception) =>
      BadRequest(Message.Error.fromExceptionMessage(exception))
    case ex @ UnauthorizedException =>
      Response[F](Status.Unauthorized).withEntity(Message.Error.fromExceptionMessage(ex)).pure[F]
    case NonFatal(exception) =>
      Logger[F].error(exception)(exception.getMessage) >>
        InternalServerError(Message.Error.fromExceptionMessage(exception))
  }

  private lazy val logInfo: ((CommitId, CommitSyncRequest)) => F[Unit] = {
    case (commitId, CommitSyncRequest(project)) =>
      Logger[F].info(
        s"Push event for eventId = $commitId, projectId = ${project.id}, projectSlug = ${project.slug} -> accepted"
      )
  }
}

object Endpoint {

  def apply[F[_]: Async: Logger: MetricsRegistry](hookTokenCrypto: HookTokenCrypto[F]): F[Endpoint[F]] =
    eventlog.api.events
      .Client[F]
      .map(new EndpointImpl[F](hookTokenCrypto, _))

  private implicit val projectDecoder: Decoder[Project] = cursor => {
    import io.renku.tinytypes.json.TinyTypeDecoders._
    (cursor.downField("id").as[GitLabId], cursor.downField("path_with_namespace").as[Slug])
      .mapN(Project(_, _))
  }

  implicit val pushEventDecoder: Decoder[(CommitId, CommitSyncRequest)] = cursor => {
    import io.renku.tinytypes.json.TinyTypeDecoders._
    for {
      commitId <- cursor.downField("after").as[CommitId]
      project  <- cursor.downField("project").as[Project]
    } yield commitId -> CommitSyncRequest(project)
  }
}
