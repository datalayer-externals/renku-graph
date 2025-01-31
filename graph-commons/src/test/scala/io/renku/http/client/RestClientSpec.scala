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

package io.renku.http.client

import cats.effect.{IO, Ref}
import cats.syntax.all._
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.http.Fault._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.collection.NonEmpty
import io.circe.{Decoder, DecodingFailure, Json}
import io.renku.config.ServiceUrl
import io.renku.control.Throttler
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.http.client.RestClientError._
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Warn
import io.renku.logging.{ExecutionTimeRecorder, TestExecutionTimeRecorder}
import io.renku.metrics.Histogram
import io.renku.stubbing.ExternalServiceStubbing
import io.renku.testtools.IOSpec
import io.renku.tinytypes.ByteArrayTinyType
import io.renku.tinytypes.TestTinyTypes.ByteArrayTestType
import io.renku.tinytypes.contenttypes.ZippedContent
import org.http4s.MediaType._
import org.http4s.Method.{GET, POST}
import org.http4s.circe.jsonOf
import org.http4s.{multipart => _, _}
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.log4cats.Logger

import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Random

class RestClientSpec
    extends AnyWordSpec
    with IOSpec
    with ExternalServiceStubbing
    with MockFactory
    with should.Matchers
    with OptionValues {

  "send" should {

    "succeed returning value calculated with the given response mapping rules " +
      "if the response matches the rules" in new TestCase {

        stubFor {
          get("/resource")
            .willReturn(ok("1"))
        }

        givenHistogramObservesExecutionTime(maybeLabel = None)

        client.callRemote(mapResponseToInt).unsafeRunSync() shouldBe 1

        verifyThrottling()

        logger.loggedOnly(Warn(s"GET $hostUrl/resource finished${executionTimeRecorder.executionTimeInfo}"))
      }

    "succeed returning value calculated with the given response mapping rules " +
      "and do not measure execution time if TimeRecorder not given" in new TestCase {

        stubFor {
          get("/resource")
            .willReturn(ok("1"))
        }

        override lazy val client = new TestRestClient(hostUrl, throttler, maybeTimeRecorder = None)

        client.callRemote(mapResponseToInt).unsafeRunSync() shouldBe 1

        verifyThrottling()

        logger.expectNoLogs()
      }

    "succeed returning value calculated with the given response mapping rules and " +
      "log execution time along with the given request name if Time Recorder present" in new TestCase {

        stubFor {
          get("/resource")
            .willReturn(ok("1"))
        }

        val requestName: String Refined NonEmpty = "some request"

        givenHistogramObservesExecutionTime(maybeLabel = requestName.value.some)

        client.callRemote(requestName).unsafeRunSync() shouldBe 1

        verifyThrottling()

        logger.loggedOnly(Warn(s"$requestName finished${executionTimeRecorder.executionTimeInfo}"))
      }

    "cause the given histogram to capture execution time - case with some given label" in new TestCase {

      stubFor {
        get("/resource")
          .willReturn(ok("1"))
      }

      val requestName: String Refined NonEmpty = "some request"

      givenHistogramObservesExecutionTime(requestName.value.some)

      client.callRemote(requestName).unsafeRunSync() shouldBe 1

      verifyThrottling()
    }

    "cause the given histogram to capture execution time - case without label" in new TestCase {

      stubFor {
        get("/resource")
          .willReturn(ok("1"))
      }

      override lazy val histogram = mock[Histogram[IO]]

      givenHistogramObservesExecutionTime(maybeLabel = None)

      client.callRemote(mapResponseToInt).unsafeRunSync() shouldBe 1

      verifyThrottling()
    }

    "fail if remote responds with status which does not match the response mapping rules" in new TestCase {

      stubFor {
        get("/resource")
          .willReturn(
            aResponse
              .withStatus(Status.NotFound.code)
              .withBody("some body")
          )
      }

      intercept[UnexpectedResponseException] {
        client.callRemote(mapResponseToInt).unsafeRunSync()
      }.getMessage shouldBe s"GET $hostUrl/resource returned ${Status.NotFound}; body: some body"

      verifyThrottling()
    }

    "fail if remote responds with an empty body and status which doesn't match the response mapping rules" in new TestCase {

      stubFor {
        get("/resource")
          .willReturn(noContent())
      }

      intercept[UnexpectedResponseException] {
        client.callRemote(mapResponseToInt).unsafeRunSync()
      }.getMessage shouldBe s"GET $hostUrl/resource returned ${Status.NoContent}; body: "

      verifyThrottling()
    }

    "fail if remote responds with a BAD_REQUEST and it's not mapped in the given response mapping rules" in new TestCase {

      val responseBody = nonBlankStrings().generateOne
      stubFor {
        get("/resource")
          .willReturn(aResponse.withStatus(Status.BadRequest.code).withBody(responseBody))
      }

      intercept[BadRequestException] {
        client.callRemote(mapResponseToInt).unsafeRunSync()
      }.getMessage shouldBe s"GET $hostUrl/resource returned ${Status.BadRequest}; body: $responseBody"

      verifyThrottling()
    }

    "fail if remote responds with a body which causes exception during mapping" in new TestCase {

      stubFor {
        get("/resource")
          .willReturn(ok("non int"))
      }

      val exception = intercept[MappingException] {
        client.callRemote(mapResponseToInt).unsafeRunSync()
      }

      verifyThrottling()

      exception.getMessage shouldBe s"""GET $hostUrl/resource returned ${Status.Ok}; error: For input string: "non int""""
      exception.getCause shouldBe a[NumberFormatException]
    }

    "fail if remote responds with a body which causes json parsing failures" in new TestCase {

      val jsonBody = Json.fromBoolean(Random.nextBoolean()).noSpaces
      stubFor {
        get("/resource")
          .willReturn(okJson(jsonBody))
      }

      val customDecodingFailure = nonEmptyStrings().generateOne
      implicit val decoder: Decoder[Boolean] = Decoder.instance(_ => DecodingFailure(customDecodingFailure, Nil).asLeft)
      implicit val entityDecoder: EntityDecoder[IO, Boolean] = jsonOf[IO, Boolean]

      lazy val mapResponseToBoolean: PartialFunction[(Status, Request[IO], Response[IO]), IO[Boolean]] = {
        case (Status.Ok, _, response) => response.as[Boolean]
      }

      val exception = intercept[MappingException] {
        client.callRemote(mapResponseToBoolean).unsafeRunSync()
      }

      verifyThrottling()

      exception.getMessage should startWith(s"""GET $hostUrl/resource returned ${Status.Ok}; error: """)
      exception.getMessage should include(s" $jsonBody")
      exception.getMessage should endWith(s" $customDecodingFailure")
    }

    "fail after retrying if there is a persistent connectivity problem" in {

      implicit val logger: TestLogger[IO] = TestLogger[IO]()

      val exceptionMessage = "Connection refused"

      val exception = intercept[ConnectivityException] {
        new TestRestClient(ServiceUrl("http://localhost:1024"), Throttler.noThrottling, None)
          .callRemote(mapResponseToInt)
          .unsafeRunSync()
      }
      exception.getMessage shouldBe s"GET http://localhost:1024/resource error: Connection refused"
      exception.getCause   shouldBe a[ConnectException]

      logger.loggedOnly(
        Warn(s"GET http://localhost:1024/resource timed out -> retrying attempt 1 error: $exceptionMessage"),
        Warn(s"GET http://localhost:1024/resource timed out -> retrying attempt 2 error: $exceptionMessage")
      )
    }

    Fault.values().filterNot(_ == MALFORMED_RESPONSE_CHUNK) foreach { fault =>
      s"fail after retrying if there is a persistent $fault problem" in new TestCase {

        stubFor {
          get("/resource")
            .willReturn(aResponse withFault fault)
        }

        val exception = intercept[ConnectivityException] {
          client.callRemote(mapResponseToInt).unsafeRunSync()
        }

        verifyThrottling()

        val causeMessage = exception.getCause.getMessage

        logger.loggedOnly(
          Warn(s"GET $hostUrl/resource timed out -> retrying attempt 1 error: $causeMessage"),
          Warn(s"GET $hostUrl/resource timed out -> retrying attempt 2 error: $causeMessage")
        )
      }
    }

    "use the overridden idle timeout" in new TestCase {

      val idleTimeout = 500 millis

      stubFor {
        get("/resource")
          .willReturn(ok("1").withFixedDelay((idleTimeout.toMillis * 2).toInt))
      }

      val exception = intercept[ClientException] {
        new TestRestClient(hostUrl,
                           Throttler.noThrottling,
                           maybeTimeRecorder = None,
                           idleTimeoutOverride = idleTimeout.some
        ).callRemote(mapResponseToInt).unsafeRunSync()
      }

      exception          shouldBe a[ClientException]
      exception.getCause shouldBe a[TimeoutException]
      exception.getMessage should not be empty
    }

    "use the overridden request timeout" in new TestCase {

      val requestTimeout = 500 millis

      stubFor {
        get("/resource")
          .willReturn(ok("1").withFixedDelay((requestTimeout.toMillis + 500).toInt))
      }

      val exception = intercept[ClientException] {
        new TestRestClient(hostUrl,
                           Throttler.noThrottling,
                           maybeTimeRecorder = None,
                           maybeRequestTimeoutOverride = requestTimeout.some
        ).callRemote(mapResponseToInt).unsafeRunSync()
      }

      exception          shouldBe a[ClientException]
      exception.getCause shouldBe a[TimeoutException]
      exception.getMessage should not be empty
    }
  }

  "multipart builder" should {
    "successfully build a multipart request" in new TestCase {

      val jsonPart = nonEmptyStrings().generateOne -> jsons.generateOne
      val textPart = nonEmptyStrings().generateOne -> nonEmptyStrings().generateOne
      val zippedPart =
        nonEmptyStrings().generateOne -> nonEmptyStrings()
          .map(_.getBytes(StandardCharsets.UTF_8))
          .map(ByteArrayTestType)
          .generateOne

      stubFor {
        post("/resource")
          .withMultipartRequestBody(
            aMultipart(jsonPart._1)
              .withBody(equalToJson(jsonPart._2.noSpaces))
              .withHeader("Content-Type", equalTo(s"${application.json.mainType}/${application.json.subType}"))
          )
          .withMultipartRequestBody(
            aMultipart(textPart._1)
              .withBody(equalTo(textPart._2))
              .withHeader("Content-Type", equalTo(s"${text.plain.mainType}/${text.plain.subType}"))
          )
          .withMultipartRequestBody(
            aMultipart(zippedPart._1)
              .withBody(binaryEqualTo(zippedPart._2.value))
              .withHeader("Content-Type", equalTo(s"${application.zip.mainType}/${application.zip.subType}"))
          )
          .withHeader(
            "Content-Type",
            containing(s"${multipart.`form-data`.mainType}/${multipart.`form-data`.subType}")
          )
          .willReturn(ok("1"))
      }

      givenHistogramObservesExecutionTime(maybeLabel = None)

      client.callMultipartEndpoint(jsonPart, textPart, zippedPart).unsafeRunSync() shouldBe 1

      verify {
        postRequestedFor(urlEqualTo("/resource"))
      }

      verifyThrottling()
    }
  }

  private trait TestCase {
    lazy val histogram        = mock[Histogram[IO]]
    private val throttlerUsed = Ref.unsafe[IO, Boolean](false)
    val throttler = new Throttler[IO, Any] {
      override def throttle[O](value: IO[O]): IO[O] = throttlerUsed.set(true) >> value
    }
    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    lazy val executionTimeRecorder = TestExecutionTimeRecorder[IO](Some(histogram))
    lazy val client                = new TestRestClient(hostUrl, throttler, Some(executionTimeRecorder))

    def verifyThrottling() =
      withClue("throttler called:") {
        throttlerUsed.get.unsafeRunSync() shouldBe true
      }

    def givenHistogramObservesExecutionTime(maybeLabel: Option[String]) =
      (histogram
        .observe(_: Option[String], _: FiniteDuration))
        .expects(maybeLabel, *)
        .returning(().pure[IO])
  }

  private lazy val hostUrl = ServiceUrl(externalServiceBaseUrl)

  lazy val mapResponseToInt: PartialFunction[(Status, Request[IO], Response[IO]), IO[Int]] = {
    case (Status.Ok, _, response) => response.as[String].map(_.toInt)
  }

  private class TestRestClient(hostUrl:                     ServiceUrl,
                               throttler:                   Throttler[IO, Any],
                               maybeTimeRecorder:           Option[ExecutionTimeRecorder[IO]],
                               idleTimeoutOverride:         Option[Duration] = None,
                               maybeRequestTimeoutOverride: Option[Duration] = None
  )(implicit logger: Logger[IO])
      extends RestClient(throttler,
                         maybeTimeRecorder,
                         retryInterval = 1 millisecond,
                         maxRetries = 2,
                         idleTimeoutOverride,
                         maybeRequestTimeoutOverride
      ) {

    def callRemote[O](mapping: PartialFunction[(Status, Request[IO], Response[IO]), IO[O]]): IO[O] = for {
      uri         <- validateUri(s"$hostUrl/resource")
      accessToken <- send(request(GET, uri))(mapping)
    } yield accessToken

    def callRemote(requestName: String Refined NonEmpty): IO[Int] = for {
      uri         <- validateUri(s"$hostUrl/resource")
      accessToken <- send(HttpRequest(request(GET, uri), requestName))(mapResponseToInt)
    } yield accessToken

    def callMultipartEndpoint(jsonPart:   (String, Json),
                              textPart:   (String, String),
                              zippedPart: (String, ByteArrayTinyType with ZippedContent)
    ): IO[Int] = for {
      uri <- validateUri(s"$hostUrl/resource")
      request <- request(POST, uri).withMultipartBuilder
                   .addPart(jsonPart._1, jsonPart._2)
                   .addPart(textPart._1, textPart._2)
                   .addPart(zippedPart._1, zippedPart._2)
                   .build()
      response <- send(request)(mapResponseToInt)
    } yield response

  }
}
