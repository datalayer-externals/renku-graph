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
package producers

import EventsSender.SendingResult.{Delivered, Misdelivered, TemporarilyUnavailable}
import TestCategoryEvent._
import cats.effect.IO
import cats.syntax.all._
import com.github.tomakehurst.wiremock.client.WireMock._
import io.renku.events.CategoryName
import io.renku.events.Generators.categoryNames
import io.renku.events.Subscription.SubscriberUrl
import io.renku.generators.Generators._
import io.renku.generators.Generators.Implicits._
import io.renku.http.client.RestClientError.ClientException
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Error
import io.renku.interpreters.TestLogger.LogMessage.MessageAndThrowable
import io.renku.metrics.LabeledGauge
import io.renku.stubbing.ExternalServiceStubbing
import io.renku.testtools.IOSpec
import org.http4s.Status._
import org.http4s.multipart.Part
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class EventsSenderSpec
    extends AnyWordSpec
    with IOSpec
    with ExternalServiceStubbing
    with MockFactory
    with should.Matchers {

  "sendEvent" should {

    s"return Delivered if remote responds with $Accepted" in new TestCase {

      val (eventJson, eventPayload) = expectEventEncoding(event)
      stubFor {
        post("/")
          .withMultipartRequestBody(
            aMultipart("event")
              .withBody(equalToJson(eventJson.spaces2))
          )
          .withMultipartRequestBody(
            aMultipart("payload")
              .withBody(equalTo(eventPayload))
          )
          .willReturn(aResponse().withStatus(Accepted.code))
      }

      (sentEventsGauge.increment _).expects(categoryName).returning(().pure[IO])

      sender.sendEvent(subscriberUrl, event).unsafeRunSync() shouldBe Delivered
    }

    TooManyRequests +: ServiceUnavailable +: NotFound +: BadGateway +: Nil foreach { status =>
      s"return TemporarilyUnavailable if remote responds with $status" in new TestCase {

        val (eventJson, eventPayload) = expectEventEncoding(event)

        stubFor {
          post("/")
            .withMultipartRequestBody(
              aMultipart("event")
                .withBody(equalToJson(eventJson.spaces2))
            )
            .withMultipartRequestBody(
              aMultipart("payload")
                .withBody(equalTo(eventPayload))
            )
            .willReturn(aResponse().withStatus(status.code))
        }

        sender.sendEvent(subscriberUrl, event).unsafeRunSync() shouldBe TemporarilyUnavailable
      }
    }

    "return Misdelivered if call to the remote fails with ConnectivityException" in new TestCase {
      override val sender =
        new EventsSenderImpl(categoryName, categoryEventEncoder, sentEventsGauge, retryInterval = 10 millis)

      expectEventEncoding(event)

      sender
        .sendEvent(SubscriberUrl("http://unexisting"), event)
        .unsafeRunSync() shouldBe Misdelivered
    }

    "return TemporarilyUnavailable if call to the remote fails with exception other than ConnectivityException" in new TestCase {

      val (eventJson, eventPayload) = expectEventEncoding(event)

      stubFor {
        post("/")
          .withMultipartRequestBody(
            aMultipart("event")
              .withBody(equalToJson(eventJson.spaces2))
          )
          .withMultipartRequestBody(
            aMultipart("payload")
              .withBody(equalTo(eventPayload))
          )
          .willReturn(aResponse().withFixedDelay((requestTimeout.toMillis + 500).toInt))
      }

      sender
        .sendEvent(subscriberUrl, event)
        .unsafeRunSync() shouldBe TemporarilyUnavailable

      logger.getMessages(Error).map {
        case MessageAndThrowable(message, cause) =>
          message shouldBe s"$categoryName: sending $event to $subscriberUrl failed"
          cause   shouldBe a[ClientException]
        case other => fail(s"Did not expect log statement: $other")
      }
    }

    s"fail if remote responds with $BadRequest" in new TestCase {

      val (eventJson, eventPayload) = expectEventEncoding(event)
      stubFor {
        post("/")
          .withMultipartRequestBody(
            aMultipart("event")
              .withBody(equalToJson(eventJson.spaces2))
          )
          .withMultipartRequestBody(
            aMultipart("payload")
              .withBody(equalTo(eventPayload))
          )
          .willReturn(badRequest().withBody("message"))
      }

      intercept[Exception] {
        sender.sendEvent(subscriberUrl, event).unsafeRunSync()
      }.getMessage shouldBe s"POST $subscriberUrl returned $BadRequest; body: message"
    }
  }

  private trait TestCase {
    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val categoryName         = categoryNames.generateOne
    val requestTimeout       = 500 millis
    val event                = testCategoryEvents.generateOne
    val subscriberUrl        = SubscriberUrl(externalServiceBaseUrl)
    val categoryEventEncoder = mock[EventEncoder[TestCategoryEvent]]
    val sentEventsGauge      = mock[LabeledGauge[IO, CategoryName]]
    val sender = new EventsSenderImpl[IO, TestCategoryEvent](categoryName,
                                                             categoryEventEncoder,
                                                             sentEventsGauge,
                                                             requestTimeoutOverride = Some(requestTimeout)
    )

    def expectEventEncoding(event: TestCategoryEvent) = {
      val eventJson    = jsons.generateOne
      val eventPayload = nonEmptyStrings().generateOne
      (categoryEventEncoder.encodeParts[IO] _)
        .expects(event)
        .returning(Vector(Part.formData[IO]("event", eventJson.noSpaces), Part.formData[IO]("payload", eventPayload)))

      (eventJson, eventPayload)
    }
  }
}
