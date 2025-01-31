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

package io.renku.triplesgenerator.events.consumers.tsmigrationrequest
package migrations
package lucenereindex

import cats.Applicative
import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all._
import io.renku.eventlog
import io.renku.eventlog.api.events.StatusChangeEvent
import io.renku.graph.model.projects
import io.renku.metrics.MetricsRegistry
import io.renku.triplesgenerator.errors.ProcessingRecoverableError
import io.renku.triplesstore._
import org.typelevel.log4cats.Logger
import tooling._

private class ReindexLucene[F[_]: Async: Logger](
    migrationName:        Migration.Name,
    backlogCreator:       BacklogCreator[F],
    projectsFinder:       ProjectsPageFinder[F],
    progressFinder:       ProgressFinder[F],
    envReadinessChecker:  EnvReadinessChecker[F],
    elClient:             eventlog.api.events.Client[F],
    projectDonePersister: ProjectDonePersister[F],
    executionRegister:    MigrationExecutionRegister[F],
    recoveryStrategy:     RecoverableErrorsRecovery = RecoverableErrorsRecovery
) extends RegisteredMigration[F](migrationName, executionRegister, recoveryStrategy) {

  private val applicative = Applicative[F]
  import applicative.whenA
  import envReadinessChecker._
  import fs2._
  import progressFinder.{findLeftInBacklog, findProgressInfo}
  import projectDonePersister._
  import projectsFinder._
  import recoveryStrategy._

  protected[lucenereindex] override def migrate(): EitherT[F, ProcessingRecoverableError, Unit] = EitherT {
    findLeftInBacklog.flatMap(cnt => whenA(cnt == 0)(backlogCreator.createBacklog())) >>
      Logger[F].info(show"$categoryName: $name backlog created") >>
      Stream
        .iterate(1)(_ + 1)
        .evalMap(_ => nextProjectsPage)
        .takeThrough(_.nonEmpty)
        .flatMap(in => Stream.emits(in))
        .evalMap(slug => findProgressInfo.map(slug -> _))
        .evalTap { case (slug, info) => logInfo(show"processing project '$slug'; waiting for free resources", info) }
        .evalTap(_ => envReadyToTakeEvent)
        .evalTap { case (slug, info) => logInfo(show"sending RedoProjectTransformation event for '$slug'", info) }
        .evalTap(sendRedoProjectTransformation)
        .evalTap { case (slug, _) => noteDone(slug) }
        .evalTap { case (slug, info) => logInfo(show"event sent for '$slug'", info) }
        .compile
        .drain
        .map(_.asRight[ProcessingRecoverableError])
        .recoverWith(maybeRecoverableError[F, Unit])
  }

  private lazy val sendRedoProjectTransformation: ((projects.Slug, String)) => F[Unit] = { case (slug, _) =>
    elClient.send(StatusChangeEvent.RedoProjectTransformation(slug))
  }

  private def logInfo(message: String, progressInfo: String): F[Unit] =
    Logger[F].info(show"$name - $progressInfo - $message")
}

private[migrations] object ReindexLucene {

  def apply[F[_]: Async: Logger: MetricsRegistry: SparqlQueryTimeRecorder](suffix: String): F[Migration[F]] = for {
    migrationName        <- Migration.Name(s"Reindex Lucene $suffix").pure[F]
    backlogCreator       <- BacklogCreator[F](migrationName)
    projectsFinder       <- ProjectsPageFinder[F](migrationName)
    progressFinder       <- ProgressFinder[F](migrationName)
    envReadinessChecker  <- EnvReadinessChecker[F]
    elClient             <- eventlog.api.events.Client[F]
    projectDonePersister <- ProjectDonePersister[F](migrationName)
    executionRegister    <- MigrationExecutionRegister[F]
  } yield new ReindexLucene(migrationName,
                            backlogCreator,
                            projectsFinder,
                            progressFinder,
                            envReadinessChecker,
                            elClient,
                            projectDonePersister,
                            executionRegister
  )
}
