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

package io.renku.triplesgenerator.events.consumers.cleanup

import cats.effect.{Async, MonadCancelThrow}
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.events.consumers.ProcessExecutor
import io.renku.events.consumers.subscriptions.SubscriptionMechanism
import io.renku.events.{CategoryName, consumers}
import io.renku.graph.model.{RenkuUrl, datasets}
import io.renku.lock.Lock
import io.renku.metrics.MetricsRegistry
import io.renku.lock.syntax._
import io.renku.triplesgenerator.TgDB.TsWriteLock
import io.renku.triplesgenerator.api.events.CleanUpEvent
import io.renku.triplesgenerator.events.consumers.TSReadinessForEventsChecker
import io.renku.triplesgenerator.events.consumers.tsmigrationrequest.migrations.reprovisioning.ReProvisioningStatus
import io.renku.triplesstore.{ProjectSparqlClient, SparqlQueryTimeRecorder}
import org.typelevel.log4cats.Logger

private class EventHandler[F[_]: MonadCancelThrow: Logger](
    override val categoryName: CategoryName,
    tsReadinessChecker:        TSReadinessForEventsChecker[F],
    eventProcessor:            EventProcessor[F],
    subscriptionMechanism:     SubscriptionMechanism[F],
    processExecutor:           ProcessExecutor[F],
    tsWriteLock:               TsWriteLock[F]
) extends consumers.EventHandlerWithProcessLimiter[F](processExecutor) {

  protected override type Event = CleanUpEvent

  override def createHandlingDefinition(): EventHandlingDefinition =
    EventHandlingDefinition(
      decode = _.event.as[CleanUpEvent],
      process = tsWriteLock.contramap[Event](_.project.slug).surround(e => eventProcessor.process(e.project)),
      precondition = tsReadinessChecker.verifyTSReady,
      onRelease = subscriptionMechanism.renewSubscription().some
    )
}

private object EventHandler {

  def apply[F[_]: Async: ReProvisioningStatus: Logger: MetricsRegistry: SparqlQueryTimeRecorder](
      subscriptionMechanism: SubscriptionMechanism[F],
      tsWriteLock:           TsWriteLock[F],
      topSameAsLock:         Lock[F, datasets.TopmostSameAs],
      projectSparqlClient:   ProjectSparqlClient[F]
  )(implicit renkuUrl: RenkuUrl): F[consumers.EventHandler[F]] = for {
    tsReadinessChecker <- TSReadinessForEventsChecker[F]
    eventProcessor     <- EventProcessor[F](topSameAsLock, projectSparqlClient)
    processExecutor    <- ProcessExecutor.concurrent(processesCount = 1)
  } yield new EventHandler[F](
    categoryName,
    tsReadinessChecker,
    eventProcessor,
    subscriptionMechanism,
    processExecutor,
    tsWriteLock
  )
}
