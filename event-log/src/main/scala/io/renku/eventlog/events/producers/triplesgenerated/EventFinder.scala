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

package io.renku.eventlog.events.producers
package triplesgenerated

import cats.Id
import cats.data._
import cats.effect.Async
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import io.renku.db.implicits._
import io.renku.db.{DbClient, SqlStatement}
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.events.producers
import io.renku.eventlog.events.producers.DefaultSubscribers.DefaultSubscribers
import io.renku.eventlog.events.producers.ProjectPrioritisation.{Priority, ProjectInfo}
import io.renku.eventlog.metrics.{EventStatusGauges, QueriesExecutionTimes}
import io.renku.graph.model.events.EventStatus._
import io.renku.graph.model.events.{CompoundEventId, EventDate, EventId, EventStatus, ExecutionDate}
import io.renku.graph.model.projects
import skunk._
import skunk.codec.all.{int4, int8}
import skunk.data.Completion
import skunk.implicits._

import java.time.Instant
import scala.math.BigDecimal.RoundingMode
import scala.util.Random

private class EventFinderImpl[F[_]: Async: SessionResource: QueriesExecutionTimes: EventStatusGauges](
    now:                   () => Instant = () => Instant.now,
    projectsFetchingLimit: Int Refined Positive,
    projectPrioritisation: ProjectPrioritisation[F],
    pickRandomlyFrom:      List[ProjectIds] => Option[ProjectIds] = ids => ids.get((Random nextInt ids.size).toLong)
) extends DbClient(Some(QueriesExecutionTimes[F]))
    with producers.EventFinder[F, TriplesGeneratedEvent]
    with SubscriptionTypeSerializers {

  import projectPrioritisation._

  override def popEvent(): F[Option[TriplesGeneratedEvent]] = SessionResource[F].useK {
    for {
      (maybeProject, maybeTriplesGeneratedEvent) <- findEventAndUpdateForProcessing
      _ <- Kleisli.liftF(maybeUpdateMetrics(maybeProject, maybeTriplesGeneratedEvent))
    } yield maybeTriplesGeneratedEvent
  }

  private def findEventAndUpdateForProcessing = for {
    maybeProject <- selectCandidateProject
    maybeIdAndProjectAndBody <- maybeProject
                                  .map(findNewestEvent)
                                  .getOrElse(Kleisli.pure(Option.empty[TriplesGeneratedEvent]))
    maybeBody <- markAsTransformingTriples(maybeIdAndProjectAndBody)
  } yield maybeProject -> maybeBody

  private def selectCandidateProject = for {
    projects       <- findProjectsWithEventsInQueue
    totalOccupancy <- findTotalOccupancy
  } yield ((prioritise _).tupled >>> selectProject)(projects, totalOccupancy)

  private def findProjectsWithEventsInQueue = measureExecutionTime {
    SqlStatement
      .named(s"${SubscriptionCategory.categoryName.value.toLowerCase} - find projects")
      .select[ExecutionDate *: ExecutionDate *: Int *: EmptyTuple, ProjectInfo](
        sql"""
        SELECT p.project_id, p.project_slug, p.latest_event_date,
          (SELECT count(event_id) FROM event evt_int WHERE evt_int.project_id = p.project_id AND evt_int.status = '#${TransformingTriples.value}') AS current_occupancy
        FROM (
          SELECT DISTINCT evt.project_id, MAX(evt.event_date) AS max_event_date
          FROM event evt
          JOIN event_payload evt_payload ON evt.event_id = evt_payload.event_id AND evt.project_id = evt_payload.project_id
          WHERE evt.status IN ('#${TriplesGenerated.value}', '#${TransformationRecoverableFailure.value}')
            AND evt.execution_date <= $executionDateEncoder
          GROUP BY evt.project_id
        ) candidate_projects
        JOIN project p ON p.project_id = candidate_projects.project_id
        JOIN LATERAL (
          SELECT COUNT(ee.event_id) AS count
          FROM event ee
          WHERE ee.project_id = candidate_projects.project_id
            AND ee.event_date > candidate_projects.max_event_date
            AND ee.status IN ('#${TransformingTriples.value}', '#${TriplesStore.value}', '#${TransformationRecoverableFailure.value}', '#${AwaitingDeletion.value}', '#${Deleting.value}')
        ) younger_statuses_final ON 1 = 1
        JOIN LATERAL (
          SELECT COUNT(ee.event_id) AS count
          FROM event ee
          WHERE ee.project_id = candidate_projects.project_id
            AND ee.execution_date <= $executionDateEncoder
            AND ee.event_date = candidate_projects.max_event_date
            AND ee.status IN ('#${TriplesGenerated.value}', '#${TransformationRecoverableFailure.value}')
        ) same_date_statuses_to_do ON 1 = 1
        WHERE younger_statuses_final.count = 0
          AND same_date_statuses_to_do.count > 0
        ORDER BY p.latest_event_date DESC
        LIMIT $int4
        """
          .query(projectIdDecoder ~ projectSlugDecoder ~ eventDateDecoder ~ int8)
          .map {
            case (id: projects.GitLabId) ~ (slug: projects.Slug) ~ (eventDate: EventDate) ~ (currentOccupancy: Long) =>
              ProjectInfo(id, slug, eventDate, Refined.unsafeApply(currentOccupancy.toInt))
          }
      )
      .arguments(ExecutionDate(now()) *: ExecutionDate(now()) *: projectsFetchingLimit.value *: EmptyTuple)
      .build(_.toList)
  }

  private def findTotalOccupancy = measureExecutionTime {
    SqlStatement
      .named(s"${SubscriptionCategory.categoryName.value.toLowerCase} - find total occupancy")
      .select[Void, Long](
        sql"""SELECT COUNT(event_id) FROM event WHERE status = '#${TransformingTriples.value}'""".query(int8)
      )
      .arguments(Void)
      .build[Id](_.unique)
  }

  private def findNewestEvent(idAndSlug: ProjectIds) = measureExecutionTime {
    val executionDate = ExecutionDate(now())
    SqlStatement
      .named(s"${SubscriptionCategory.categoryName.value.toLowerCase} - find oldest")
      .select[projects.Slug *: projects.GitLabId *: ExecutionDate *: ExecutionDate *: EmptyTuple,
              TriplesGeneratedEvent
      ](
        sql"""
         SELECT evt.event_id, evt.project_id, $projectSlugEncoder AS project_slug, evt_payload.payload
         FROM (
           SELECT evt_int.project_id, max(event_date) AS max_event_date
           FROM event evt_int
           JOIN event_payload evt_payload ON evt_int.event_id = evt_payload.event_id AND evt_int.project_id = evt_payload.project_id
           WHERE evt_int.project_id = $projectIdEncoder
             AND #${`status IN`(TriplesGenerated, TransformationRecoverableFailure)}
             AND execution_date < $executionDateEncoder
           GROUP BY evt_int.project_id
         ) newest_event_date
         JOIN event evt ON newest_event_date.project_id = evt.project_id
           AND newest_event_date.max_event_date = evt.event_date
           AND #${`status IN`(TriplesGenerated, TransformationRecoverableFailure)}
           AND execution_date < $executionDateEncoder
         JOIN event_payload evt_payload ON evt.event_id = evt_payload.event_id AND evt.project_id = evt_payload.project_id
         LIMIT 1
         """
          .query(compoundEventIdDecoder ~ projectSlugDecoder ~ zippedPayloadDecoder)
          .map { case eventId ~ projectSlug ~ eventPayload =>
            TriplesGeneratedEvent(eventId, projectSlug, eventPayload)
          }
      )
      .arguments(idAndSlug.slug *: idAndSlug.id *: executionDate *: executionDate *: EmptyTuple)
      .build(_.option)
  }

  private def `status IN`(status: EventStatus, otherStatuses: EventStatus*) =
    s"status IN (${NonEmptyList.of(status, otherStatuses: _*).map(el => s"'$el'").toList.mkString(",")})"

  private lazy val selectProject: List[(ProjectIds, Priority)] => Option[ProjectIds] = {
    case Nil                          => None
    case (projectIdAndSlug, _) +: Nil => Some(projectIdAndSlug)
    case many                         => pickRandomlyFrom(prioritiesList(from = many))
  }

  private def prioritiesList(from: List[(ProjectIds, Priority)]): List[ProjectIds] =
    from.foldLeft(List.empty[ProjectIds]) { case (acc, (projectIdAndSlug, priority)) =>
      acc :++ List.fill((priority.value * 10).setScale(2, RoundingMode.HALF_UP).toInt)(projectIdAndSlug)
    }

  private lazy val markAsTransformingTriples
      : Option[TriplesGeneratedEvent] => Kleisli[F, Session[F], Option[TriplesGeneratedEvent]] = {
    case None =>
      Kleisli.pure(Option.empty[TriplesGeneratedEvent])
    case Some(event @ TriplesGeneratedEvent(id, _, _)) =>
      updateStatus(id) map toNoneIfEventAlreadyTaken(event)
  }

  private def updateStatus(commitEventId: CompoundEventId) = measureExecutionTime {
    SqlStatement
      .named(s"${SubscriptionCategory.categoryName.value.toLowerCase} - update status")
      .command[EventStatus *: ExecutionDate *: EventId *: projects.GitLabId *: EventStatus *: EmptyTuple](
        sql"""UPDATE event
              SET status = $eventStatusEncoder, execution_date = $executionDateEncoder
              WHERE event_id = $eventIdEncoder
                AND project_id = $projectIdEncoder
                AND status <> $eventStatusEncoder
        """.command
      )
      .arguments(
        TransformingTriples *:
          ExecutionDate(now()) *:
          commitEventId.id *:
          commitEventId.projectId *:
          TransformingTriples *:
          EmptyTuple
      )
      .build
  }

  private def toNoneIfEventAlreadyTaken(event: TriplesGeneratedEvent): Completion => Option[TriplesGeneratedEvent] = {
    case Completion.Update(1) => Some(event)
    case _                    => None
  }

  private def maybeUpdateMetrics(maybeProject: Option[ProjectIds], maybeBody: Option[TriplesGeneratedEvent]) =
    (maybeBody, maybeProject)
      .mapN { case (_, ProjectIds(_, projectSlug)) =>
        (EventStatusGauges[F].awaitingTransformation decrement projectSlug) >>
          (EventStatusGauges[F].underTransformation increment projectSlug)
      }
      .getOrElse(().pure[F])
}

private object EventFinder {

  private val ProjectsFetchingLimit: Int Refined Positive = 10

  def apply[F[_]: Async: DefaultSubscribers: SessionResource: QueriesExecutionTimes: EventStatusGauges]
      : F[producers.EventFinder[F, TriplesGeneratedEvent]] =
    ProjectPrioritisation[F]
      .map(pp => new EventFinderImpl(projectsFetchingLimit = ProjectsFetchingLimit, projectPrioritisation = pp))
}
