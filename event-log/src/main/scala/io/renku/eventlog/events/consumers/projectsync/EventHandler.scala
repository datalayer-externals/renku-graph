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

package io.renku.eventlog.events.consumers.projectsync

import cats.data.EitherT.fromEither
import cats.effect._
import cats.syntax.all._
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.events.consumers.EventSchedulingResult.Accepted
import io.renku.events.consumers.subscriptions.SubscriptionMechanism
import io.renku.events.consumers.{ConcurrentProcessExecutor, EventHandlingProcess}
import io.renku.events.{CategoryName, EventRequestContent, consumers}
import io.renku.graph.tokenrepository.AccessTokenFinder
import io.renku.http.client.GitLabClient
import io.renku.metrics.MetricsRegistry
import org.typelevel.log4cats.Logger

private class EventHandler[F[_]: Concurrent: Logger](override val categoryName: CategoryName,
                                                     projectInfoSynchronizer:    ProjectInfoSynchronizer[F],
                                                     subscriptionMechanism:      SubscriptionMechanism[F],
                                                     concurrentProcessesLimiter: ConcurrentProcessesExecutor[F]
) extends consumers.EventHandlerWithProcessLimiter[F](concurrentProcessesLimiter) {

  override def createHandlingDefinition(request: EventRequestContent): F[EventHandlingProcess[F]] =
    EventHandlingProcess.withWaitingForCompletion[F](
      startProcessingEvent(request, _),
      releaseProcess = subscriptionMechanism.renewSubscription()
    )

  import projectInfoSynchronizer._

  private def startProcessingEvent(request: EventRequestContent, processing: Deferred[F, Unit]) = for {
    event <- fromEither(request.event.getProject).map(p => ProjectSyncEvent(p.id, p.path))
    result <- Spawn[F]
                .start((syncProjectInfo(event) recoverWith logError(event)) >> processing.complete(()))
                .toRightT
                .map(_ => Accepted)
                .semiflatTap(Logger[F].log(event))
                .leftSemiflatTap(Logger[F].log(event))
  } yield result
}

private object EventHandler {
  import com.typesafe.config.{Config, ConfigFactory}
  import eu.timepit.refined.api.Refined
  import eu.timepit.refined.numeric.Positive
  import eu.timepit.refined.pureconfig._
  import io.renku.config.ConfigLoader.find

  def apply[F[
      _
  ]: Async: GitLabClient: AccessTokenFinder: SessionResource: Logger: MetricsRegistry: QueriesExecutionTimes](
      subscriptionMechanism: SubscriptionMechanism[F],
      config:                Config = ConfigFactory.load()
  ): F[EventHandler[F]] = for {
    projectInfoSynchronizer    <- ProjectInfoSynchronizer[F]
    maxConcurrentProcesses     <- find[F, Int Refined Positive]("project-sync-max-concurrent-processes", config)
    concurrentProcessesLimiter <- ConcurrentProcessExecutor[F](processesCount = maxConcurrentProcesses)
  } yield new EventHandler[F](categoryName, projectInfoSynchronizer, subscriptionMechanism, concurrentProcessesLimiter)
}
