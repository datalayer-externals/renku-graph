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

package io.renku.triplesgenerator.events.consumers.awaitinggeneration.triplesgeneration.renkulog

import cats.MonadThrow
import cats.data.EitherT
import cats.data.EitherT._
import cats.effect.Async
import cats.effect.implicits._
import cats.syntax.all._
import io.renku.graph.config.{GitLabUrlLoader, RenkuUrlLoader}
import io.renku.graph.model.{RenkuUrl, entities, projects}
import io.renku.http.client.AccessToken
import io.renku.jsonld.{JsonLD, Property}
import io.renku.triplesgenerator.errors.{ProcessingNonRecoverableError, ProcessingRecoverableError}
import io.renku.triplesgenerator.events.consumers.awaitinggeneration.{CommitEvent, logMessageCommon}
import io.renku.triplesgenerator.events.consumers.awaitinggeneration.triplesgeneration.TriplesGenerator
import io.renku.triplesgenerator.events.consumers.awaitinggeneration.triplesgeneration.renkulog.Commands.{GitLabRepoUrlFinder, GitLabRepoUrlFinderImpl, RepositoryPath}
import org.typelevel.log4cats.Logger

import java.security.SecureRandom
import scala.util.control.NonFatal

private[awaitinggeneration] class RenkuLogTriplesGenerator[F[_]: Async] private[renkulog] (
    gitRepoUrlFinder: GitLabRepoUrlFinder[F],
    renku:            Commands.Renku[F],
    file:             Commands.File[F],
    git:              Commands.Git[F],
    randomLong:       () => Long
)(implicit renkuUrl: RenkuUrl)
    extends TriplesGenerator[F] {

  import ammonite.ops.{Path, root}
  import file._
  import gitRepoUrlFinder._
  import io.renku.jsonld.syntax._

  private val workDirectory: Path = root / "tmp"
  private val repositoryDirectoryFinder = ".*/(.*)$".r
  private val gitAttributeFileName      = ".gitattributes"

  override def generateTriples(
      commitEvent: CommitEvent
  )(implicit maybeAccessToken: Option[AccessToken]): EitherT[F, ProcessingRecoverableError, JsonLD] = EitherT {
    createRepositoryDirectory(commitEvent.project.slug)
      .bracket(path => cloneCheckoutGenerate(commitEvent)(maybeAccessToken, RepositoryPath(path)))(deleteDirectory)
      .recoverWith(meaningfulError(commitEvent, maybeAccessToken))
  }

  private def cloneCheckoutGenerate(commitEvent: CommitEvent)(implicit
      maybeAccessToken: Option[AccessToken],
      repoDirectory:    RepositoryPath
  ): F[Either[ProcessingRecoverableError, JsonLD]] = {
    for {
      _ <- prepareRepository(commitEvent)
      result <- right[ProcessingRecoverableError](checkRenkuProject) >>= {
                  case true  => EitherT(cleanUpRepository() >> migrateAndLog(commitEvent).value)
                  case false => nonRenkuProjectPayload(commitEvent)
                }
    } yield result
  }.value

  private def prepareRepository(commitEvent: CommitEvent)(implicit
      maybeAccessToken: Option[AccessToken],
      repoDirectory:    RepositoryPath
  ): EitherT[F, ProcessingRecoverableError, Unit] = for {
    repositoryUrl <- liftF(findRepositoryUrl(commitEvent.project.slug))
    _             <- git clone (repositoryUrl, workDirectory)
    _             <- liftF(git checkout commitEvent.commitId)
  } yield ()

  private def checkRenkuProject(implicit repoDirectory: RepositoryPath): F[Boolean] =
    file.exists(repoDirectory.value / ".renku")

  private def cleanUpRepository()(implicit repoDirectory: RepositoryPath): F[Unit] = {
    val gitAttributeFilePath = repoDirectory.value / gitAttributeFileName
    (file exists gitAttributeFilePath) >>= {
      case false => MonadThrow[F].unit
      case true =>
        git.status.map(status => !status.contains("nothing to commit")) >>= {
          case true =>
            for {
              _ <- git rm gitAttributeFilePath
              _ <- git.`reset --hard`
              _ <- git.`rm --cached`
              _ <- git.`add -A`
              _ <- git commit "fixing dirty repo problem"
            } yield ()
          case false => MonadThrow[F].unit
        }
    }
  }

  private def migrateAndLog(
      commitEvent: CommitEvent
  )(implicit repoDirectory: RepositoryPath): EitherT[F, ProcessingRecoverableError, JsonLD] = for {
    _       <- liftF(renku migrate commitEvent)
    triples <- renku.graphExport
  } yield triples

  private def nonRenkuProjectPayload(commitEvent: CommitEvent): EitherT[F, ProcessingRecoverableError, JsonLD] =
    EitherT {
      JsonLD
        .entity(
          projects.ResourceId(commitEvent.project.slug).asEntityId,
          entities.Project.entityTypes,
          Map.empty[Property, JsonLD]
        )
        .flatten
        .fold(_.raiseError[F, Either[ProcessingRecoverableError, JsonLD]],
              _.asRight[ProcessingRecoverableError].pure[F].widen
        )
    }

  private def createRepositoryDirectory(projectSlug: projects.Slug): F[Path] =
    mkdir(tempDirectoryName(repositoryNameFrom(projectSlug)))

  private def tempDirectoryName(repositoryName: String) =
    workDirectory / s"$repositoryName-${randomLong()}"

  private def repositoryNameFrom(projectSlug: projects.Slug): String = projectSlug.value match {
    case repositoryDirectoryFinder(folderName) => folderName
  }

  private def meaningfulError(
      commitEvent:      CommitEvent,
      maybeAccessToken: Option[AccessToken]
  ): PartialFunction[Throwable, F[Either[ProcessingRecoverableError, JsonLD]]] = {
    case ProcessingNonRecoverableError.MalformedRepository(message, cause) =>
      ProcessingNonRecoverableError
        .MalformedRepository(s"${logMessageCommon(commitEvent)} $message", cause)
        .raiseError[F, Either[ProcessingRecoverableError, JsonLD]]
    case NonFatal(exception) =>
      (Option(exception.getMessage) -> maybeAccessToken)
        .mapN { (message, token) =>
          if (message contains token.value)
            new Exception(
              s"${logMessageCommon(commitEvent)} triples generation failed: ${message.replaceAll(token.value, token.toString)}"
            )
          else
            new Exception(s"${logMessageCommon(commitEvent)} triples generation failed", exception)
        }
        .getOrElse(new Exception(s"${logMessageCommon(commitEvent)} triples generation failed", exception))
        .raiseError[F, Either[ProcessingRecoverableError, JsonLD]]
  }
}

private[events] object RenkuLogTriplesGenerator {

  def apply[F[_]: Async: Logger](): F[TriplesGenerator[F]] = for {
    gitLabUrl <- GitLabUrlLoader[F]()
    renkuUrl  <- RenkuUrlLoader[F]()
  } yield {
    implicit val baseUrl: RenkuUrl = renkuUrl
    new RenkuLogTriplesGenerator(
      new GitLabRepoUrlFinderImpl[F](gitLabUrl),
      Commands.Renku[F],
      Commands.File[F],
      Commands.Git[F],
      randomLong = new SecureRandom().nextLong _
    )
  }
}
