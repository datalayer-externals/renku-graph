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

package io.renku.eventlog.eventdetails

import cats.MonadThrow
import cats.effect.Async
import eu.timepit.refined.auto._
import io.renku.db.{DbClient, SqlStatement}
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.TypeSerializers
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.graph.model.events.{CompoundEventId, EventBody, EventDetails, EventId}
import io.renku.graph.model.projects
import skunk._
import skunk.implicits._

private trait EventDetailsFinder[F[_]] {
  def findDetails(eventId: CompoundEventId): F[Option[EventDetails]]
}

private class EventDetailsFinderImpl[F[_]: Async: SessionResource: QueriesExecutionTimes]
    extends DbClient[F](Some(QueriesExecutionTimes[F]))
    with EventDetailsFinder[F]
    with TypeSerializers {

  override def findDetails(eventId: CompoundEventId): F[Option[EventDetails]] =
    SessionResource[F].useK(measureExecutionTime(find(eventId)))

  private def find(eventId: CompoundEventId) =
    SqlStatement[F](name = "find event details")
      .select[EventId *: projects.GitLabId *: EmptyTuple, EventDetails](
        sql"""SELECT evt.event_id, evt.project_id, evt.event_body
                FROM event evt WHERE evt.event_id = $eventIdEncoder and evt.project_id = $projectIdEncoder
          """.query(compoundEventIdDecoder ~ eventBodyDecoder).map {
          case (eventId: CompoundEventId, eventBody: EventBody) => EventDetails(eventId, eventBody)
        }
      )
      .arguments(eventId.id *: eventId.projectId *: EmptyTuple)
      .build(_.option)
}

private object EventDetailsFinder {
  def apply[F[_]: Async: SessionResource: QueriesExecutionTimes]: F[EventDetailsFinder[F]] =
    MonadThrow[F].catchNonFatal(new EventDetailsFinderImpl[F])
}
