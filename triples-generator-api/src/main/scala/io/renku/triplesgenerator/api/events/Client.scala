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

package io.renku.triplesgenerator.api.events

import cats.effect.Async
import cats.syntax.all._
import io.renku.events.producers.EventSender
import io.renku.events.EventRequestContent
import io.renku.graph.config.TriplesGeneratorUrl
import io.renku.metrics.MetricsRegistry
import org.typelevel.log4cats.Logger

trait Client[F[_]] {
  def send(event: ProjectViewedEvent):     F[Unit]
  def send(event: ProjectViewingDeletion): F[Unit]
}

object Client {
  def apply[F[_]: Async: Logger: MetricsRegistry]: F[Client[F]] =
    EventSender[F](TriplesGeneratorUrl)
      .map(new ClientImpl[F](_))
}

private class ClientImpl[F[_]](eventSender: EventSender[F]) extends Client[F] {

  import cats.syntax.all._
  import io.circe.syntax._
  import EventSender.EventContext

  override def send(event: ProjectViewedEvent): F[Unit] =
    eventSender.sendEvent(
      EventRequestContent.NoPayload(event.asJson),
      EventContext(ProjectViewedEvent.categoryName,
                   show"${ProjectViewedEvent.categoryName}: sending event $event failed"
      )
    )

  override def send(event: ProjectViewingDeletion): F[Unit] =
    eventSender.sendEvent(
      EventRequestContent.NoPayload(event.asJson),
      EventContext(ProjectViewingDeletion.categoryName,
                   show"${ProjectViewingDeletion.categoryName}: sending event $event failed"
      )
    )
}
