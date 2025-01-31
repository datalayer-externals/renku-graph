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

package io.renku.eventlog.events.consumers.cleanuprequest

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.metrics.QueriesExecutionTimes
import org.typelevel.log4cats.Logger

private trait EventProcessor[F[_]] {
  def process(event: CleanUpRequestEvent): F[Unit]
}

private object EventProcessor {
  def apply[F[_]: Async: SessionResource: QueriesExecutionTimes: Logger]: F[EventProcessor[F]] = for {
    projectIdFinder <- ProjectIdFinder[F]
    queue           <- CleanUpEventsQueue[F]
  } yield new EventProcessorImpl[F](projectIdFinder, queue)
}

private class EventProcessorImpl[F[_]: MonadThrow: Logger](projectIdFinder: ProjectIdFinder[F],
                                                           queue: CleanUpEventsQueue[F]
) extends EventProcessor[F] {

  import projectIdFinder._

  override def process(event: CleanUpRequestEvent): F[Unit] =
    Logger[F].info(show"$categoryName: $event accepted") >>
      enqueue(event)

  private def enqueue(event: CleanUpRequestEvent) = event match {
    case CleanUpRequestEvent.Full(id, slug) => queue.offer(id, slug)
    case CleanUpRequestEvent.Partial(slug) =>
      findProjectId(slug) >>= {
        case Some(id) => queue.offer(id, slug)
        case None     => Logger[F].warn(show"Cannot find projectId for $slug")
      }
  }
}
