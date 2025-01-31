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

package io.renku.triplesgenerator.events.consumers.triplesgenerated

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow, NonEmptyParallel, Parallel}
import io.circe.DecodingFailure
import io.renku.graph.config.RenkuUrlLoader
import io.renku.graph.model.RenkuUrl
import io.renku.graph.model.entities._
import io.renku.graph.model.gitlab.GitLabProjectInfo
import io.renku.http.client.{AccessToken, GitLabClient}
import io.renku.triplesgenerator.errors.{ProcessingNonRecoverableError, ProcessingRecoverableError}
import io.renku.triplesgenerator.events.consumers.projectinfo.ProjectInfoFinder
import org.typelevel.log4cats.Logger

private trait EntityBuilder[F[_]] {
  def buildEntity(event: TriplesGeneratedEvent)(implicit
      maybeAccessToken: Option[AccessToken]
  ): EitherT[F, ProcessingRecoverableError, Project]
}

private class EntityBuilderImpl[F[_]: MonadThrow](
    projectInfoFinder: ProjectInfoFinder[F],
    renkuUrl:          RenkuUrl
) extends EntityBuilder[F] {

  private implicit val renkuUrlImplicit: RenkuUrl       = renkuUrl
  private val applicative:               Applicative[F] = Applicative[F]

  import applicative._

  override def buildEntity(event: TriplesGeneratedEvent)(implicit
      maybeAccessToken: Option[AccessToken]
  ): EitherT[F, ProcessingRecoverableError, Project] = for {
    projectInfo <- findValidProjectInfo(event)
    project     <- extractProject(projectInfo, event)
  } yield project

  private def findValidProjectInfo(event: TriplesGeneratedEvent)(implicit
      maybeAccessToken: Option[AccessToken]
  ) = projectInfoFinder.findProjectInfo(event.project.slug) semiflatMap {
    case Some(projectInfo) => projectInfo.pure[F]
    case None =>
      ProcessingNonRecoverableError
        .MalformedRepository(show"${event.project} not found in GitLab")
        .raiseError[F, GitLabProjectInfo]
  }

  private def extractProject(projectInfo: GitLabProjectInfo, event: TriplesGeneratedEvent) =
    EitherT.right[ProcessingRecoverableError] {
      for {
        projects <-
          event.payload.cursor.as(ProjectJsonLDDecoder.list(projectInfo)).fold(raiseError(event), _.pure[F])
        project <- projects match {
                     case project :: Nil => project.pure[F]
                     case other =>
                       ProcessingNonRecoverableError
                         .MalformedRepository(
                           show"${other.size} Project entities found in the JsonLD for ${event.project}"
                         )
                         .raiseError[F, Project]
                   }
        _ <- whenA(event.project.slug.value.toLowerCase() != project.slug.value.toLowerCase())(
               ProcessingNonRecoverableError
                 .MalformedRepository(
                   show"Event for project ${event.project} contains payload for project ${project.slug}"
                 )
                 .raiseError[F, Unit]
             )
      } yield project
    }

  private def raiseError[T](event: TriplesGeneratedEvent): DecodingFailure => F[T] = failure =>
    new ProcessingNonRecoverableError.MalformedRepository(
      show"Finding Project entity in the JsonLD for ${event.project} failed",
      failure
    ).raiseError[F, T]
}

private object EntityBuilder {
  def apply[F[_]: Async: NonEmptyParallel: GitLabClient: Parallel: Logger]: F[EntityBuilder[F]] = for {
    renkuUrl          <- RenkuUrlLoader[F]()
    projectInfoFinder <- ProjectInfoFinder[F]
  } yield new EntityBuilderImpl[F](projectInfoFinder, renkuUrl)
}
