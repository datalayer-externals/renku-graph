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

package io.renku.eventlog.events.consumers.migrationstatuschange

import cats.effect.{Async, MonadCancelThrow}
import cats.syntax.all._
import io.circe.{Decoder, DecodingFailure}
import io.renku.config.ServiceVersion
import io.renku.eventlog.{MigrationMessage, MigrationStatus}
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.MigrationStatus._
import io.renku.eventlog.events.consumers.migrationstatuschange.{Event => CategoryEvent}
import io.renku.eventlog.events.consumers.migrationstatuschange.Event.{ToDone, ToNonRecoverableFailure, ToRecoverableFailure}
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.events.{consumers, CategoryName}
import io.renku.events.consumers.ProcessExecutor
import io.renku.events.Subscription.SubscriberUrl
import org.typelevel.log4cats.Logger

private class EventHandler[F[_]: MonadCancelThrow: Logger](
    statusUpdater:             StatusUpdater[F],
    override val categoryName: CategoryName = categoryName
) extends consumers.EventHandlerWithProcessLimiter[F](ProcessExecutor.sequential) {

  protected override type Event = CategoryEvent

  import statusUpdater._

  override def createHandlingDefinition(): EventHandlingDefinition =
    EventHandlingDefinition(
      decode = _.event.as[Event],
      process = ev => Logger[F].info(show"$categoryName: $ev accepted") >> updateStatus(ev)
    )

  private implicit val eventDecoder: Decoder[Event] = { cursor =>
    for {
      url     <- cursor.downField("subscriber").downField("url").as[SubscriberUrl]
      version <- cursor.downField("subscriber").downField("version").as[ServiceVersion]
      event <- cursor.downField("newStatus").as[MigrationStatus] >>= {
                 case Done => ToDone(url, version).asRight
                 case NonRecoverableFailure =>
                   cursor.downField("message").as[MigrationMessage].map(ToNonRecoverableFailure(url, version, _))
                 case RecoverableFailure =>
                   cursor.downField("message").as[MigrationMessage].map(ToRecoverableFailure(url, version, _))
                 case other => DecodingFailure(show"Cannot change migration status to $other", Nil).asLeft
               }
    } yield event
  }
}

private object EventHandler {
  def apply[F[_]: Async: SessionResource: Logger: QueriesExecutionTimes]: F[consumers.EventHandler[F]] =
    StatusUpdater[F].map(new EventHandler[F](_))
}
