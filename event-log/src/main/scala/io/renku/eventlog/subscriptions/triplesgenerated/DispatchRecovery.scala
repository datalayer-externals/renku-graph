/*
 * Copyright 2021 Swiss Data Science Center (SDSC)
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

package io.renku.eventlog.subscriptions.triplesgenerated

import cats.effect.{Bracket, IO, Timer}
import cats.syntax.all._
import ch.datascience.db.{DbTransactor, SqlQuery}
import ch.datascience.events.consumers.subscriptions.SubscriberUrl
import ch.datascience.graph.model.projects
import ch.datascience.metrics.{LabeledGauge, LabeledHistogram}
import io.chrisdavenport.log4cats.Logger
import io.renku.eventlog.statuschange.commands.{ChangeStatusCommand, ToTransformationNonRecoverableFailure, UpdateResult}
import io.renku.eventlog.statuschange.{IOUpdateCommandsRunner, StatusUpdatesRunner}
import io.renku.eventlog.subscriptions.DispatchRecovery
import io.renku.eventlog.{EventLogDB, EventMessage, subscriptions}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

private class DispatchRecoveryImpl[Interpretation[_]](
    underTriplesTransformationGauge: LabeledGauge[Interpretation, projects.Path],
    statusUpdatesRunner:             StatusUpdatesRunner[Interpretation],
    logger:                          Logger[Interpretation],
    onErrorSleep:                    FiniteDuration
)(implicit ME:                       Bracket[Interpretation, Throwable], timer: Timer[Interpretation])
    extends subscriptions.DispatchRecovery[Interpretation, TriplesGeneratedEvent] {

  override def recover(
      url:   SubscriberUrl,
      event: TriplesGeneratedEvent
  ): PartialFunction[Throwable, Interpretation[Unit]] = { case NonFatal(exception) =>
    val markEventFailed = ToTransformationNonRecoverableFailure[Interpretation](
      event.id,
      EventMessage(exception),
      underTriplesTransformationGauge,
      maybeProcessingTime = None
    )
    for {
      _ <- statusUpdatesRunner run markEventFailed recoverWith retry(markEventFailed)
      _ <- logger.error(exception)(s"${SubscriptionCategory.name}: $event, url = $url -> ${markEventFailed.status}")
    } yield ()
  }

  private def retry(
      command: ChangeStatusCommand[Interpretation]
  ): PartialFunction[Throwable, Interpretation[UpdateResult]] = { case NonFatal(exception) =>
    {
      for {
        _      <- logger.error(exception)(s"${SubscriptionCategory.name}: Marking event as ${command.status} failed")
        _      <- timer sleep onErrorSleep
        result <- statusUpdatesRunner run command
      } yield result
    } recoverWith retry(command)
  }
}

private object DispatchRecovery {

  private val OnErrorSleep: FiniteDuration = 1 seconds

  def apply(transactor:                      DbTransactor[IO, EventLogDB],
            underTriplesTransformationGauge: LabeledGauge[IO, projects.Path],
            queriesExecTimes:                LabeledHistogram[IO, SqlQuery.Name],
            logger:                          Logger[IO]
  )(implicit timer:                          Timer[IO]): IO[DispatchRecovery[IO, TriplesGeneratedEvent]] = for {
    updateCommandRunner <- IOUpdateCommandsRunner(transactor, queriesExecTimes, logger)
  } yield new DispatchRecoveryImpl[IO](underTriplesTransformationGauge, updateCommandRunner, logger, OnErrorSleep)
}
