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

package io.renku.eventlog.events

import cats.effect.IO
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.circe.Json
import io.renku.data.Message
import io.renku.events.EventRequestContent
import io.renku.events.Generators.eventRequestContents
import io.renku.events.consumers.ConsumersModelGenerators.badRequests
import io.renku.events.consumers.EventSchedulingResult.SchedulingError
import io.renku.events.consumers.{EventConsumersRegistry, EventSchedulingResult}
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.EventsGenerators.zippedEventPayloads
import io.renku.http.client.RestClient._
import io.renku.http.server.EndpointTester._
import io.renku.testtools.IOSpec
import io.renku.tinytypes.ByteArrayTinyType
import io.renku.tinytypes.contenttypes.ZippedContent
import org.http4s.MediaType._
import org.http4s.Status._
import org.http4s._
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.http4s.multipart.Part
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

class EventEndpointSpec
    extends AnyWordSpec
    with IOSpec
    with MockFactory
    with should.Matchers
    with TableDrivenPropertyChecks {

  "processEvent" should {

    s"$BadRequest if the request is not a multipart request" in new TestCase {

      val response = endpoint.processEvent(Request()).unsafeRunSync()

      response.status                      shouldBe BadRequest
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error("Not multipart request")
    }

    s"$BadRequest if there is no event part in the request" in new TestCase {

      override lazy val request =
        Request[IO]().addParts(Part.formData[IO](nonEmptyStrings().generateOne, nonEmptyStrings().generateOne))

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe BadRequest
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error("Missing event part")
    }

    s"$BadRequest if there the event part in the request is malformed" in new TestCase {

      override lazy val request = Request[IO]().addParts(Part.formData[IO]("event", ""))

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe BadRequest
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error("Malformed event body")
    }

    val scenarios = Table(
      "Request content name" -> "Request content",
      "no payload"           -> jsons.map(EventRequestContent.NoPayload),
      "string payload" -> (jsons -> nonEmptyStrings()).mapN { case (event, payload) =>
        EventRequestContent.WithPayload(event, payload)
      },
      "zipped payload" -> (jsons -> zippedEventPayloads).mapN { case (event, payload) =>
        EventRequestContent.WithPayload(event, payload)
      }
    )
    forAll(scenarios) { (scenarioName, requestContents) =>
      s"$Accepted if one of the handlers accepts the given $scenarioName request" in new TestCase {
        override val requestContent: EventRequestContent = requestContents.generateOne

        (eventConsumersRegistry.handle _)
          .expects(where(eventRequestEquals(requestContent)))
          .returning(EventSchedulingResult.Accepted.pure[IO])

        val response = (request >>= endpoint.processEvent).unsafeRunSync()

        response.status                      shouldBe Accepted
        response.contentType                 shouldBe Some(`Content-Type`(application.json))
        response.as[Message].unsafeRunSync() shouldBe Message.Info("Event accepted")
      }
    }

    s"$BadRequest if none of the handlers supports the given payload" in new TestCase {

      (eventConsumersRegistry.handle _)
        .expects(*)
        .returning(EventSchedulingResult.UnsupportedEventType.pure[IO])

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe BadRequest
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error("Unsupported Event Type")
    }

    s"$BadRequest if one of the handlers supports the given payload but it's malformed" in new TestCase {

      val badRequest = badRequests.generateOne
      (eventConsumersRegistry.handle _)
        .expects(*)
        .returning(badRequest.pure[IO])

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe BadRequest
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error.unsafeApply(badRequest.reason)
    }

    s"$TooManyRequests if the handler returns ${EventSchedulingResult.Busy}" in new TestCase {

      (eventConsumersRegistry.handle _)
        .expects(*)
        .returning(EventSchedulingResult.Busy.pure[IO])

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe TooManyRequests
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Info("Too many events to handle")
    }

    s"$ServiceUnavailable if the handler returns EventSchedulingResult.ServiceUnavailable" in new TestCase {

      val handlingResult = EventSchedulingResult.ServiceUnavailable(nonEmptyStrings().generateOne)

      (eventConsumersRegistry.handle _)
        .expects(*)
        .returning(handlingResult.pure[IO])

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe ServiceUnavailable
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error.unsafeApply(handlingResult.reason)
    }

    s"$InternalServerError if the handler returns $SchedulingError" in new TestCase {

      (eventConsumersRegistry.handle _)
        .expects(*)
        .returning(SchedulingError(exceptions.generateOne).pure[IO])

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe InternalServerError
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error("Failed to schedule event")
    }

    s"$InternalServerError if the handler fails" in new TestCase {

      (eventConsumersRegistry.handle _)
        .expects(*)
        .returning(exceptions.generateOne.raiseError[IO, EventSchedulingResult])

      val response = (request >>= endpoint.processEvent).unsafeRunSync()

      response.status                      shouldBe InternalServerError
      response.contentType                 shouldBe Some(`Content-Type`(application.json))
      response.as[Message].unsafeRunSync() shouldBe Message.Error("Failed to schedule event")
    }
  }

  private trait TestCase {
    val requestContent = eventRequestContents.generateOne

    private lazy val multipartParts: Vector[Part[IO]] = requestContent match {
      case EventRequestContent.NoPayload(event) =>
        Vector(
          implicitly[PartEncoder[Json]].encode("event", event)
        )
      case EventRequestContent.WithPayload(event, payload: String) =>
        Vector(
          implicitly[PartEncoder[Json]].encode("event", event),
          implicitly[PartEncoder[String]].encode("payload", payload)
        )
      case EventRequestContent.WithPayload(event, payload: ByteArrayTinyType with ZippedContent) =>
        Vector(
          implicitly[PartEncoder[Json]].encode("event", event),
          implicitly[PartEncoder[ByteArrayTinyType with ZippedContent]].encode("payload", payload)
        )
      case event: EventRequestContent.WithPayload[_] => fail(s"Unsupported EventRequestContent payload type $event")
    }

    lazy val request = Request[IO](Method.POST, uri"events").addParts(multipartParts: _*)

    val eventConsumersRegistry = mock[EventConsumersRegistry[IO]]
    val endpoint               = new EventEndpointImpl[IO](eventConsumersRegistry)
  }

  private def eventRequestEquals(eventRequestContent: EventRequestContent): EventRequestContent => Boolean = {
    case requestContent @ EventRequestContent.NoPayload(_)              => requestContent == eventRequestContent
    case requestContent @ EventRequestContent.WithPayload(_, _: String) => requestContent == eventRequestContent
    case EventRequestContent.WithPayload(event, actualPayload: ByteArrayTinyType) =>
      eventRequestContent match {
        case EventRequestContent.WithPayload(_, expectedPayload: ByteArrayTinyType) =>
          eventRequestContent.event == event && (actualPayload.value sameElements expectedPayload.value)
        case _ => false
      }
    case _ => false
  }
}
