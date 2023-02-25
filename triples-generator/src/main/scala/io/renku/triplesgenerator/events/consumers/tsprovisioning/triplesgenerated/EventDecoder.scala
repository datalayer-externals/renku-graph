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

package io.renku.triplesgenerator.events.consumers.tsprovisioning.triplesgenerated

import cats.syntax.all._
import io.renku.compression.Zip
import io.renku.events.EventRequestContent
import io.renku.graph.model.events.ZippedEventPayload
import io.renku.jsonld.parser
import io.renku.jsonld.parser.ParsingFailure

private trait EventDecoder {
  def decode(request: EventRequestContent): Either[Exception, TriplesGeneratedEvent]
}

private object EventDecoder {
  def apply(): EventDecoder = new EventDecoderImpl()
}

private class EventDecoderImpl(zip: Zip = Zip) extends EventDecoder {

  import io.renku.events.consumers.EventDecodingTools._

  def decode(request: EventRequestContent): Either[Exception, TriplesGeneratedEvent] = for {
    eventId <- request.event.getEventId
    project <- request.event.getProject
    payload <- request match {
                 case EventRequestContent.WithPayload(_, payload: ZippedEventPayload) => payload.asRight
                 case _ => new Exception(show"Event $eventId without or invalid payload").asLeft
               }
    unzipped <- zip.unzip(payload.value)
    payload <- parser.parse(unzipped).leftMap { error =>
                 ParsingFailure(s"Event $eventId cannot be decoded", error)
               }
  } yield TriplesGeneratedEvent(eventId.id, project, payload)
}
