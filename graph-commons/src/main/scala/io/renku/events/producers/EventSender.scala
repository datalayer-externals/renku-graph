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

package io.renku.events.producers

import cats.effect.{Async, Temporal}
import cats.syntax.all._
import cats.{Applicative, Eval}
import com.typesafe.config.{Config, ConfigFactory}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import io.renku.control.Throttler
import io.renku.events.producers.EventSender.EventContext
import io.renku.events.{CategoryName, EventRequestContent}
import io.renku.graph.config.{EventConsumerUrl, EventConsumerUrlFactory}
import io.renku.graph.metrics.SentEventsGauge
import io.renku.http.client.RestClient
import io.renku.http.client.RestClient.{MaxRetriesAfterConnectionTimeout, SleepAfterConnectionIssue}
import io.renku.http.client.RestClientError.{ClientException, ConnectivityException, UnexpectedResponseException}
import io.renku.metrics.MetricsRegistry
import org.http4s.Method.POST
import org.http4s.Status.{Accepted, BadGateway, GatewayTimeout, NotFound, ServiceUnavailable, TooManyRequests}
import org.http4s._
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

trait EventSender[F[_]] {

  def sendEvent(eventContent: EventRequestContent.NoPayload, context: EventContext): F[Unit]

  def sendEvent[PayloadType](eventContent: EventRequestContent.WithPayload[PayloadType], context: EventContext)(implicit
      partEncoder: RestClient.PartEncoder[PayloadType]
  ): F[Unit]
}

object EventSender {

  def apply[F[_]: Async: Logger: MetricsRegistry](
      consumerUrlFactory: EventConsumerUrlFactory,
      config:             Config
  ): F[EventSender[F]] = for {
    consumerUrl     <- consumerUrlFactory(config)
    sentEventsGauge <- SentEventsGauge[F]
  } yield new EventSenderImpl(consumerUrl, sentEventsGauge, onBusySleep = 15 seconds, onErrorSleep = 30 seconds)

  def apply[F[_]: Async: Logger: MetricsRegistry](consumerUrlFactory: EventConsumerUrlFactory): F[EventSender[F]] =
    apply(consumerUrlFactory, ConfigFactory.load)

  final case class EventContext(categoryName: CategoryName,
                                errorMessage: String,
                                retries:      Option[EventContext.Retries]
  ) {
    def nextAttempt():        EventContext = copy(retries = retries.map(_.nextAttempt()))
    lazy val hasAttemptsLeft: Boolean      = retries.forall(_.hasAttemptsLeft)
  }

  object EventContext {

    final case class Retries(max: Int, left: Int) {
      def nextAttempt():        Retries = copy(left = left - 1)
      lazy val hasAttemptsLeft: Boolean = left > 0
    }

    def apply(categoryName: CategoryName, errorMessage: String): EventContext =
      EventContext(categoryName, errorMessage, retries = None)

    def apply(categoryName: CategoryName, errorMessage: String, maxRetriesNumber: Int): EventContext =
      EventContext(categoryName, errorMessage, Retries(maxRetriesNumber, maxRetriesNumber).some)
  }
}

class EventSenderImpl[F[_]: Async: Logger](
    eventConsumerUrl:       EventConsumerUrl,
    sentEventsGauge:        SentEventsGauge[F],
    onErrorSleep:           FiniteDuration,
    onBusySleep:            FiniteDuration,
    retryInterval:          FiniteDuration = SleepAfterConnectionIssue,
    maxRetries:             Int Refined NonNegative = MaxRetriesAfterConnectionTimeout,
    requestTimeoutOverride: Option[Duration] = None
) extends RestClient[F, Any](Throttler.noThrottling,
                             retryInterval = retryInterval,
                             maxRetries = maxRetries,
                             requestTimeoutOverride = requestTimeoutOverride
    )
    with EventSender[F] {

  private val applicative = Applicative[F]
  import applicative.whenA

  override def sendEvent(eventContent: EventRequestContent.NoPayload, context: EventContext): F[Unit] = for {
    uri            <- validateUri(s"$eventConsumerUrl/events")
    request        <- createRequest(uri, eventContent)
    responseStatus <- sendWithRetry(request, context)
    _              <- whenA(responseStatus == Accepted)(sentEventsGauge.increment(context.categoryName))
  } yield ()

  override def sendEvent[PayloadType](eventContent: EventRequestContent.WithPayload[PayloadType],
                                      context:      EventContext
  )(implicit partEncoder: RestClient.PartEncoder[PayloadType]): F[Unit] = for {
    uri            <- validateUri(s"$eventConsumerUrl/events")
    request        <- createRequest(uri, eventContent)
    responseStatus <- sendWithRetry(request, context)
    _              <- whenA(responseStatus == Accepted)(sentEventsGauge.increment(context.categoryName))
  } yield ()

  private def createRequest(uri: Uri, eventRequestContent: EventRequestContent.NoPayload) =
    request(POST, uri).withMultipartBuilder
      .addPart("event", eventRequestContent.event)
      .build()

  private def createRequest[PayloadType](uri: Uri, eventRequestContent: EventRequestContent.WithPayload[PayloadType])(
      implicit partEncoder: RestClient.PartEncoder[PayloadType]
  ) = request(POST, uri).withMultipartBuilder
    .addPart("event", eventRequestContent.event)
    .addPart("payload", eventRequestContent.payload)
    .build()

  private def sendWithRetry(request: Request[F], context: EventContext): F[Status] =
    send(request)(responseMapping)
      .recoverWith { ex =>
        val updatedContext = context.nextAttempt()
        retryOnServerError(Eval.always(sendWithRetry(request, updatedContext)), updatedContext)(ex)
      }

  private def retryOnServerError(retry:   Eval[F[Status]],
                                 context: EventContext
  ): PartialFunction[Throwable, F[Status]] = {
    case exception if !context.hasAttemptsLeft =>
      val message = s"${context.errorMessage} - no more attempts left"
      Logger[F].error(exception)(message) >> new Exception(message, exception).raiseError[F, Status]
    case UnexpectedResponseException(TooManyRequests, _) =>
      waitAndRetry(retry, context.errorMessage)
    case exception @ UnexpectedResponseException(ServiceUnavailable | GatewayTimeout | BadGateway, _) =>
      waitAndRetry(retry, exception, context.errorMessage)
    case exception @ (_: ConnectivityException | _: ClientException) =>
      waitAndRetry(retry, exception, context.errorMessage)
  }

  private def waitAndRetry(retry: Eval[F[Status]], message: String): F[Status] =
    Logger[F].info(s"$message - consumer busy") >> waitAndRetry(retry, onBusySleep)

  private def waitAndRetry(retry: Eval[F[Status]], exception: Throwable, errorMessage: String): F[Status] =
    Logger[F].error(exception)(errorMessage) >> waitAndRetry(retry, onErrorSleep)

  private def waitAndRetry(retry: Eval[F[Status]], sleep: Duration): F[Status] =
    Temporal[F].delayBy(retry.value, sleep)

  private lazy val responseMapping: PartialFunction[(Status, Request[F], Response[F]), F[Status]] = {
    case (Accepted, _, _) => Accepted.pure[F]
    case (NotFound, _, _) => NotFound.pure[F]
  }
}
