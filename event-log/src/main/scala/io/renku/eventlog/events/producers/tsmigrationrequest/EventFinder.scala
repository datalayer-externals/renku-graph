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

package io.renku.eventlog.events.producers.tsmigrationrequest

import cats.{Id, MonadThrow}
import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.all._
import io.renku.config.ServiceVersion
import io.renku.db.{DbClient, SqlStatement}
import io.renku.db.implicits._
import io.renku.eventlog.{ChangeDate, MigrationStatus, TSMigtationTypeSerializers}
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.MigrationStatus._
import io.renku.eventlog.events.producers
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.events.Subscription.SubscriberUrl
import skunk._
import skunk.codec.all.int8
import skunk.data.Completion
import skunk.implicits._

import java.time.{Duration, Instant}

private trait EventFinder[F[_]] extends producers.EventFinder[F, MigrationRequestEvent]

private class EventFinderImpl[F[_]: Async: SessionResource: QueriesExecutionTimes](
    now: () => Instant = () => Instant.now
) extends DbClient(Some(QueriesExecutionTimes[F]))
    with EventFinder[F]
    with TSMigtationTypeSerializers {

  private val SentStatusTimeout        = Duration ofMinutes 1
  private val RecoverableStatusTimeout = Duration ofSeconds 30

  override def popEvent(): F[Option[MigrationRequestEvent]] =
    SessionResource[F].useWithTransactionK[Option[MigrationRequestEvent]] {
      Kleisli { case (transaction, session) =>
        for {
          savepoint           <- transaction.savepoint
          maybeEventCandidate <- tryToPop(session)
          maybeEvent <- verifySingleEventTaken(maybeEventCandidate)(session) >>= {
                          case true  => transaction.commit >> maybeEventCandidate.pure[F]
                          case false => (transaction rollback savepoint).map(_ => Option.empty[MigrationRequestEvent])
                        }
        } yield maybeEvent
      }
    }

  private def tryToPop = findEvent >>= {
    case Some(event) => markTaken(event) map toNoneIfTaken(event)
    case _           => Kleisli.pure(Option.empty[MigrationRequestEvent])
  }

  private type SubscriptionRecord = (SubscriberUrl, ServiceVersion, MigrationStatus, ChangeDate)

  private def findEvent = findEvents.map(findCandidate).map(toEvent)

  private def findEvents = measureExecutionTime {
    SqlStatement
      .named(s"${categoryName.value.toLowerCase} - find events")
      .select[Void, SubscriptionRecord](
        sql"""SELECT m.subscriber_url, m.subscriber_version, m.status, m.change_date
              FROM (
                SELECT subscriber_version
                FROM ts_migration
                ORDER BY change_date DESC
                LIMIT 1
              ) latest 
              JOIN ts_migration m ON m.subscriber_version = latest.subscriber_version 
              ORDER BY m.change_date DESC
      """.query(subscriberUrlDecoder ~ serviceVersionDecoder ~ migrationStatusDecoder ~ changeDateDecoder)
          .map { case url ~ version ~ status ~ changeDate => (url, version, status, changeDate) }
      )
      .arguments(Void)
      .build(_.toList)
  }

  private lazy val findCandidate: List[SubscriptionRecord] => Option[SubscriptionRecord] = {
    case Nil => None
    case records =>
      val groupedByStatus = records.groupBy(_._3)
      if (groupedByStatus contains MigrationStatus.Done) None
      else if (groupedByStatus contains MigrationStatus.Sent)
        groupedByStatus(MigrationStatus.Sent).find(olderThan(SentStatusTimeout))
      else if (groupedByStatus contains MigrationStatus.RecoverableFailure)
        (groupedByStatus
          .get(MigrationStatus.RecoverableFailure)
          .map(_.filter(olderThan(RecoverableStatusTimeout))) combine groupedByStatus.get(MigrationStatus.New))
          .flatMap(_.sortBy(_._4.value).reverse.headOption)
      else groupedByStatus.get(MigrationStatus.New).flatMap(_.sortBy(_._4.value).reverse.headOption)
  }

  private def olderThan(duration: Duration): SubscriptionRecord => Boolean = { case (_, _, _, date) =>
    (Duration.between(date.value, now()) compareTo duration) >= 0
  }

  private lazy val toEvent: Option[SubscriptionRecord] => Option[MigrationRequestEvent] =
    _.map { case (url, version, _, _) => MigrationRequestEvent(url, version) }

  private def markTaken(event: MigrationRequestEvent) = measureExecutionTime {
    SqlStatement
      .named(s"${categoryName.value.toLowerCase} - mark taken")
      .command[ChangeDate *: SubscriberUrl *: ServiceVersion *: ChangeDate *: EmptyTuple](sql"""
        UPDATE ts_migration
        SET status = '#${Sent.value}', change_date = $changeDateEncoder, message = NULL
        WHERE subscriber_url = $subscriberUrlEncoder 
          AND subscriber_version = $serviceVersionEncoder
          AND (status <> '#${Sent.value}' OR change_date < $changeDateEncoder)
        """.command)
      .arguments(
        ChangeDate(now()) *: event.subscriberUrl *: event.subscriberVersion *:
          ChangeDate(now() minus SentStatusTimeout) *: EmptyTuple
      )
      .build
      .flatMapResult {
        case Completion.Update(1) => true.pure[F]
        case Completion.Update(0) => false.pure[F]
        case completion =>
          new Exception(s"${categoryName.show}: cannot update TS migration record: $completion").raiseError[F, Boolean]
      }
  }

  private def toNoneIfTaken(event: MigrationRequestEvent): Boolean => Option[MigrationRequestEvent] = {
    case true  => event.some
    case false => None
  }

  private def verifySingleEventTaken: Option[MigrationRequestEvent] => Kleisli[F, Session[F], Boolean] = {
    case None        => Kleisli pure true
    case Some(event) => checkSingleEventTaken(event)
  }

  private def checkSingleEventTaken(event: MigrationRequestEvent) = measureExecutionTime {
    SqlStatement
      .named(s"${categoryName.value.toLowerCase} - check single sent")
      .select[ServiceVersion, Boolean](
        sql"""SELECT COUNT(DISTINCT subscriber_url)
              FROM ts_migration
              WHERE subscriber_version = $serviceVersionEncoder 
                AND status = '#${Sent.value}'
           """
          .query(int8)
          .map {
            case 0L | 1L => true
            case _       => false
          }
      )
      .arguments(event.subscriberVersion)
      .build[Id](_.unique)
  }
}

private object EventFinder {
  def apply[F[_]: Async: SessionResource: QueriesExecutionTimes]: F[EventFinder[F]] =
    MonadThrow[F].catchNonFatal(new EventFinderImpl[F]())
}
