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

package io.renku.eventlog

import cats.syntax.all._
import io.renku.eventlog.events.producers.eventdelivery._
import io.renku.events.Subscription.{SubscriberId, SubscriberUrl}
import io.renku.events.consumers.Project
import io.renku.graph.model.events._
import io.renku.graph.model.projects
import io.renku.http.rest.paging.model.PerPage
import io.renku.microservices.{MicroserviceBaseUrl, MicroserviceIdentifier}
import scodec.bits.ByteVector
import skunk.codec.all._
import skunk.{Decoder, Encoder}

import java.time.{OffsetDateTime, ZoneOffset}

object TypeSerializers extends TypeSerializers

trait TypeSerializers {

  val eventIdDecoder: Decoder[EventId] = varchar.map(EventId.apply)
  val eventIdEncoder: Encoder[EventId] = varchar.values.contramap(_.value)

  val commitIdDecoder: Decoder[CommitId] = varchar.map(CommitId.apply)
  val commitIdEncoder: Encoder[CommitId] = varchar.values.contramap(_.value)

  val projectIdDecoder: Decoder[projects.GitLabId] = int4.map(projects.GitLabId.apply)
  val projectIdEncoder: Encoder[projects.GitLabId] = int4.values.contramap(_.value)

  val projectSlugDecoder: Decoder[projects.Slug] = varchar.map(projects.Slug.apply)
  val projectSlugEncoder: Encoder[projects.Slug] =
    varchar.values.contramap((b: projects.Slug) => b.value)

  val projectIdsDecoder: Decoder[EventInfo.ProjectIds] =
    (projectIdDecoder, projectSlugDecoder).mapN(EventInfo.ProjectIds.apply)
  val projectIdsEncoder: Encoder[EventInfo.ProjectIds] =
    (projectIdEncoder, projectSlugEncoder).contramapN(a => (a.id, a.slug))

  val eventBodyDecoder: Decoder[EventBody] = text.map(EventBody.apply)
  val eventBodyEncoder: Encoder[EventBody] = text.values.contramap(_.value)

  val createdDateDecoder: Decoder[CreatedDate] = timestamptz.map(timestamp => CreatedDate(timestamp.toInstant))
  val createdDateEncoder: Encoder[CreatedDate] = timestamptz.values.contramap((b: CreatedDate) =>
    OffsetDateTime.ofInstant(b.value, b.value.atOffset(ZoneOffset.UTC).toZonedDateTime.getZone)
  )

  val executionDateDecoder: Decoder[ExecutionDate] =
    timestamptz.map(timestamp => ExecutionDate(timestamp.toInstant))
  val executionDateEncoder: Encoder[ExecutionDate] = timestamptz.values.contramap((b: ExecutionDate) =>
    OffsetDateTime.ofInstant(b.value, b.value.atOffset(ZoneOffset.UTC).toZonedDateTime.getZone)
  )

  val eventDateDecoder: Decoder[EventDate] = timestamptz.map(timestamp => EventDate(timestamp.toInstant))
  val eventDateEncoder: Encoder[EventDate] = timestamptz.values.contramap((b: EventDate) =>
    OffsetDateTime.ofInstant(b.value, b.value.atOffset(ZoneOffset.UTC).toZonedDateTime.getZone)
  )

  val batchDateDecoder: Decoder[BatchDate] = timestamptz.map(timestamp => BatchDate(timestamp.toInstant))
  val batchDateEncoder: Encoder[BatchDate] = timestamptz.values.contramap((b: BatchDate) =>
    OffsetDateTime.ofInstant(b.value, b.value.atOffset(ZoneOffset.UTC).toZonedDateTime.getZone)
  )

  val eventMessageDecoder: Decoder[EventMessage] = varchar.map(EventMessage.apply)
  val eventMessageEncoder: Encoder[EventMessage] = varchar.values.contramap(_.value)

  val eventProcessingTimeDecoder: Decoder[EventProcessingTime] = interval.map(EventProcessingTime.apply)
  val eventProcessingTimeEncoder: Encoder[EventProcessingTime] = interval.values.contramap(_.value)

  val zippedPayloadDecoder: Decoder[ZippedEventPayload] = bytea.map(ZippedEventPayload.apply)
  val zippedPayloadEncoder: Encoder[ZippedEventPayload] = bytea.values.contramap(_.value)

  val eventStatusDecoder: Decoder[EventStatus] = varchar.map(EventStatus.apply)
  val eventStatusEncoder: Encoder[EventStatus] = varchar.values.contramap(_.value)
  val processingStatusDecoder: Decoder[EventStatus.ProcessingStatus] = eventStatusDecoder.emap {
    case s: EventStatus.ProcessingStatus => s.asRight
    case s: EventStatus                  => show"'$s' is not a ProcessingStatus".asLeft
  }

  val eventProcessingStatusEncoder: Encoder[EventStatus.ProcessingStatus] =
    eventStatusEncoder.contramap((s: EventStatus.ProcessingStatus) => s: EventStatus)

  val eventFailureStatusEncoder: Encoder[EventStatus.FailureStatus] =
    eventStatusEncoder.contramap((s: EventStatus.FailureStatus) => s: EventStatus)

  val eventTypeIdDecoder: Decoder[EventTypeId] = varchar.map { case DeletingProjectTypeId.value =>
    DeletingProjectTypeId
  }
  val eventTypeIdEncoder: Encoder[EventTypeId] = varchar.values.contramap(_.value)

  val compoundEventIdDecoder: Decoder[CompoundEventId] = (eventIdDecoder *: projectIdDecoder).to[CompoundEventId]

  val projectDecoder: Decoder[Project] = (projectIdDecoder *: projectSlugDecoder).to[Project]

  val subscriberUrlDecoder: Decoder[SubscriberUrl] = varchar.map(SubscriberUrl.apply)
  val subscriberUrlEncoder: Encoder[SubscriberUrl] = varchar.values.contramap(_.value)

  val microserviceBaseUrlDecoder: Decoder[MicroserviceBaseUrl] = varchar.map(MicroserviceBaseUrl.apply)
  val microserviceBaseUrlEncoder: Encoder[MicroserviceBaseUrl] = varchar.values.contramap(_.value)

  val subscriberIdDecoder: Decoder[SubscriberId] = varchar(19).map(SubscriberId.apply)
  val subscriberIdEncoder: Encoder[SubscriberId] = varchar(19).values.contramap(_.value)

  val microserviceIdentifierDecoder: Decoder[MicroserviceIdentifier] = varchar.map(MicroserviceIdentifier.apply)
  val microserviceIdentifierEncoder: Encoder[MicroserviceIdentifier] = varchar.values.contramap(_.value)

  val microserviceUrlDecoder: Decoder[MicroserviceBaseUrl] = varchar.map(MicroserviceBaseUrl.apply)
  val microserviceUrlEncoder: Encoder[MicroserviceBaseUrl] = varchar.values.contramap(_.value)

  val perPageEncoder: Encoder[PerPage] = int4.values.contramap(_.value)

  val byteVectorDecoder: Decoder[ByteVector] = bytea.map(ByteVector.view)
}
