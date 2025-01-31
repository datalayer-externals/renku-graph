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

package io.renku.eventlog.events.consumers.creation

import cats.effect.Async
import cats.syntax.all._
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.metrics.{EventStatusGauges, QueriesExecutionTimes}
import io.renku.events.consumers
import io.renku.events.consumers.subscriptions.SubscriptionMechanism
import io.renku.metrics.MetricsRegistry
import org.typelevel.log4cats.Logger

object SubscriptionFactory {

  def apply[F[_]: Async: Logger: SessionResource: QueriesExecutionTimes: EventStatusGauges: MetricsRegistry]
      : F[(consumers.EventHandler[F], SubscriptionMechanism[F])] = for {
    handler <- EventHandler[F]
  } yield handler -> SubscriptionMechanism.noOpSubscriptionMechanism(categoryName)
}
