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

package io.renku.eventlog.events.producers.projectsync

import cats.effect.Async
import cats.syntax.all._
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.events.producers._
import eventdelivery._
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.metrics.MetricsRegistry
import org.typelevel.log4cats.Logger
import projectsync.EventEncoder.encodeEvent

private[producers] object SubscriptionCategory {

  def apply[F[_]: Async: SessionResource: UrlAndIdSubscriberTracker: Logger: MetricsRegistry: QueriesExecutionTimes]
      : F[SubscriptionCategory[F]] = for {
    subscribers      <- UrlAndIdSubscribers[F](categoryName)
    eventFinder      <- EventFinder[F]
    dispatchRecovery <- DispatchRecovery[F]
    eventDelivery    <- EventDelivery.noOp[F, ProjectSyncEvent]
    eventsDistributor <- EventsDistributor(categoryName,
                                           subscribers,
                                           eventFinder,
                                           eventDelivery,
                                           EventEncoder(encodeEvent),
                                           dispatchRecovery
                         )
    deserializer <- UrlAndIdSubscriptionDeserializer[F, SubscriptionPayload](categoryName, SubscriptionPayload.apply)
  } yield new SubscriptionCategoryImpl[F, SubscriptionPayload](categoryName,
                                                               subscribers,
                                                               eventsDistributor,
                                                               deserializer,
                                                               CapacityFinder.noOpCapacityFinder[F]
  )
}
