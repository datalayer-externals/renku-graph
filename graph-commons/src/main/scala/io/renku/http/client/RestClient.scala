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

import cats.MonadThrow
import cats.effect.{Async, Temporal}
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.NonNegative
import fs2.Stream
import fs2.io.net.Network
import io.circe.Json
import io.renku.control.Throttler
import io.renku.http.client.AccessToken._
import io.renku.http.client.RestClient._
import io.renku.http.client.RestClientError._
import io.renku.logging.ExecutionTimeRecorder
import io.renku.tinytypes.ByteArrayTinyType
import io.renku.tinytypes.contenttypes.ZippedContent
import org.http4s.AuthScheme.Bearer
import org.http4s.Credentials.Token
import org.http4s.Status.BadRequest
import org.http4s._
import org.http4s.client.{Client, ConnectionFailure}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.core.EmberException
import org.http4s.headers.{Authorization, `Content-Disposition`, `Content-Type`}
import org.http4s.multipart.{Multiparts, Part}
import org.typelevel.ci._
import org.typelevel.log4cats.Logger

import java.io.IOException
import java.net.{ConnectException, SocketException, UnknownHostException}
import java.nio.channels.ClosedChannelException
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

abstract class RestClient[F[_]: Async: Logger, ThrottlingTarget](
    throttler:              Throttler[F, ThrottlingTarget],
    maybeTimeRecorder:      Option[ExecutionTimeRecorder[F]] = None,
    retryInterval:          FiniteDuration = SleepAfterConnectionIssue,
    maxRetries:             Int Refined NonNegative = MaxRetriesAfterConnectionTimeout,
    idleTimeoutOverride:    Option[Duration] = None,
    requestTimeoutOverride: Option[Duration] = None
) {

  import HttpRequest._

  protected lazy val validateUri: String => F[Uri] = RestClient.validateUri[F]

  protected def request(method: Method, uri: Uri): Request[F] = Request[F](
    method = method,
    uri = uri
  )

  protected def request(method: Method, uri: Uri, accessToken: AccessToken): Request[F] =
    Request[F](
      method = method,
      uri = uri,
      headers = authHeader(accessToken)
    )

  protected def request(method: Method, uri: Uri, maybeAccessToken: Option[AccessToken]): Request[F] =
    maybeAccessToken match {
      case Some(accessToken) => request(method, uri, accessToken)
      case _                 => request(method, uri)
    }

  protected def secureRequest(method: Method, uri: Uri)(implicit
      maybeAccessToken: Option[AccessToken]
  ): Request[F] = request(method, uri, maybeAccessToken)

  protected def request(method: Method, uri: Uri, basicAuth: BasicAuthCredentials): Request[F] =
    Request[F](
      method = method,
      uri = uri,
      headers = Headers(basicAuthHeader(basicAuth))
    )

  private lazy val authHeader: AccessToken => Headers = {
    case ProjectAccessToken(token)   => Headers(Authorization(Token(Bearer, token)))
    case UserOAuthAccessToken(token) => Headers(Authorization(Token(Bearer, token)))
    case PersonalAccessToken(token)  => Headers(Header.Raw(ci"PRIVATE-TOKEN", token))
  }

  private def basicAuthHeader(basicAuth: BasicAuthCredentials) =
    Authorization(BasicCredentials(basicAuth.username.value, basicAuth.password.value))

  protected def send[ResultType](request: Request[F])(mapResponse: ResponseMapping[ResultType]): F[ResultType] =
    send(HttpRequest(request))(mapResponse)

  protected def send[ResultType](
      request: HttpRequest[F]
  )(mapResponse: ResponseMapping[ResultType]): F[ResultType] =
    httpClientBuilder.build.use { httpClient =>
      throttler.throttle {
        measureExecutionTime(callRemote(httpClient, request, mapResponse, attempt = 1), request)
      }
    }

  private def httpClientBuilder: EmberClientBuilder[F] = {
    implicit val network: Network[F] = Network.forAsync(Async[F])
    val clientBuilder       = EmberClientBuilder.default[F]
    val updatedIdleTimeout  = idleTimeoutOverride map clientBuilder.withIdleConnectionTime getOrElse clientBuilder
    val updatedBothTimeouts = requestTimeoutOverride map updatedIdleTimeout.withTimeout getOrElse updatedIdleTimeout
    idleTimeoutOverride -> requestTimeoutOverride match {
      case None -> None =>
        // in the case no timeout overrides given, the idle timeout is set at 10% more than the timeout to mute the http4s warning
        updatedBothTimeouts withIdleConnectionTime updatedBothTimeouts.timeout * 1.1
      case _ => updatedBothTimeouts
    }
  }

  private def measureExecutionTime[ResultType](block: => F[ResultType], request: HttpRequest[F]): F[ResultType] =
    maybeTimeRecorder match {
      case None => block
      case Some(timeRecorder) =>
        timeRecorder
          .measureExecutionTime(block, request.toHistogramLabel)
          .flatMap(timeRecorder.logExecutionTime(withMessage = LogMessage(request, "finished")))
    }

  private def callRemote[ResultType](httpClient:  Client[F],
                                     request:     HttpRequest[F],
                                     mapResponse: ResponseMapping[ResultType],
                                     attempt:     Int
  ): F[ResultType] =
    httpClient
      .run(request.request)
      .use(response => processResponse(request.request, mapResponse)(response))
      .recoverWith(connectionError(httpClient, request, mapResponse, attempt))

  private def processResponse[ResultType](request: Request[F], mapResponse: ResponseMapping[ResultType])(
      response: Response[F]
  ): F[ResultType] =
    (mapResponse orElse raiseBadRequest orElse raiseUnexpectedResponse)((response.status, request, response))
      .recoverWith(mappingError(request, response))

  private def raiseBadRequest[T]: PartialFunction[(Status, Request[F], Response[F]), F[T]] = {
    case (_, request, response) if response.status == BadRequest =>
      response
        .as[String]
        .flatMap { bodyAsString =>
          MonadThrow[F].raiseError(BadRequestException(LogMessage(request, response, bodyAsString)))
        }
  }

  private def raiseUnexpectedResponse[T]: PartialFunction[(Status, Request[F], Response[F]), F[T]] = {
    case (_, request, response) =>
      response
        .as[String]
        .flatMap { bodyAsString =>
          MonadThrow[F].raiseError(
            UnexpectedResponseException(response.status, LogMessage(request, response, bodyAsString))
          )
        }
  }

  private def mappingError[T](request: Request[F], response: Response[F]): PartialFunction[Throwable, F[T]] = {
    case error: RestClientError => MonadThrow[F].raiseError(error)
    case NonFatal(cause: InvalidMessageBodyFailure) =>
      val exception = new Exception(List(cause, cause.getCause()).map(_.getMessage()).mkString("; "), cause)
      MonadThrow[F].raiseError(MappingException(LogMessage(request, response, exception), exception))
    case NonFatal(cause) =>
      MonadThrow[F].raiseError(MappingException(LogMessage(request, response, cause), cause))
  }

  private def connectionError[T](httpClient:  Client[F],
                                 request:     HttpRequest[F],
                                 mapResponse: ResponseMapping[T],
                                 attempt:     Int
  ): PartialFunction[Throwable, F[T]] = {
    case error: RestClientError => error.raiseError[F, T]
    case ConnectionError(exception) if attempt <= maxRetries.value =>
      for {
        _      <- Logger[F].warn(LogMessage(request.request, s"timed out -> retrying attempt $attempt", exception))
        _      <- Temporal[F] sleep retryInterval
        result <- callRemote(httpClient, request, mapResponse, attempt + 1)
      } yield result
    case ConnectionError(exception) if attempt > maxRetries.value =>
      ConnectivityException(LogMessage(request.request, exception), exception).raiseError[F, T]
    case NonFatal(exception) =>
      ClientException(LogMessage(request.request, exception), exception).raiseError[F, T]
  }

  private object ConnectionError {
    def unapply(ex: Throwable): Option[Throwable] =
      ex match {
        case _: ConnectionFailure | _: ConnectException | _: SocketException | _: UnknownHostException =>
          Some(ex)
        case _: IOException
            if ex.getMessage.toLowerCase
              .contains("connection reset") || ex.getMessage.toLowerCase.contains("broken pipe") =>
          Some(ex)
        case _: EmberException.ReachedEndOfStream => Some(ex)
        case _: ClosedChannelException            => Some(ex)
        case _ => None
      }
  }

  protected type ResponseMapping[ResultType] = ResponseMappingF[F, ResultType]

  private implicit class HttpRequestOps(request: HttpRequest[F]) {
    lazy val toHistogramLabel: Option[String Refined NonEmpty] = request match {
      case UnnamedRequest(_)     => None
      case NamedRequest(_, name) => Some(name)
    }
  }

  protected object LogMessage {

    def apply(request: HttpRequest[F], message: String): String =
      request match {
        case UnnamedRequest(request) => s"${request.method} ${request.uri} $message"
        case NamedRequest(_, name)   => s"$name $message"
      }

    def apply(request: Request[F], message: String, cause: Throwable): String =
      s"${request.method} ${request.uri} $message error: ${toSingleLine(cause)}"

    def apply(request: Request[F], cause: Throwable): String =
      s"${request.method} ${request.uri} error: ${toSingleLine(cause)}"

    def apply(request: Request[F], response: Response[F], responseBody: String): String =
      s"${request.method} ${request.uri} returned ${response.status}; body: ${toSingleLine(responseBody)}"

    def apply(request: Request[F], response: Response[F], cause: Throwable): String =
      s"${request.method} ${request.uri} returned ${response.status}; error: ${toSingleLine(cause)}"

    def toSingleLine(exception: Throwable): String =
      Option(exception.getMessage) map toSingleLine getOrElse exception.getClass.getSimpleName

    def toSingleLine(string: String): String = string.split('\n').map(_.trim.filter(_ >= ' ')).mkString
  }

  implicit class RequestOps(request: Request[F]) {
    lazy val withMultipartBuilder: MultipartBuilder = new MultipartBuilder(request)

    def withParts(parts: Vector[Part[F]]): F[Request[F]] =
      new MultipartBuilder(request, parts).build()

    class MultipartBuilder private[RequestOps] (request: Request[F], parts: Vector[Part[F]] = Vector.empty[Part[F]]) {

      def addPart[PartType](name: String, value: PartType)(implicit
          encoder: PartEncoder[PartType]
      ): MultipartBuilder =
        new MultipartBuilder(request, encoder.encode[F](name, value) +: parts)

      def maybeAddPart[PartType](name: String, maybeValue: Option[PartType])(implicit
          encoder: PartEncoder[PartType]
      ): MultipartBuilder = maybeValue
        .map(addPart(name, _))
        .getOrElse(this)

      def build(): F[Request[F]] =
        Multiparts
          .forSync[F]
          .flatMap(_.multipart(parts))
          .map(multipart =>
            request
              .withEntity(multipart)
              .withHeaders(multipart.headers.headers.filterNot(_.name == ci"transfer-encoding"))
          )
    }
  }
}

object RestClient {

  import eu.timepit.refined.auto._

  import scala.concurrent.duration._

  val SleepAfterConnectionIssue:        FiniteDuration          = 10 seconds
  val MaxRetriesAfterConnectionTimeout: Int Refined NonNegative = 10

  type ResponseMappingF[F[_], ResultType] =
    PartialFunction[(Status, Request[F], Response[F]), F[ResultType]]

  def validateUri[F[_]: MonadThrow](uri: String): F[Uri] =
    MonadThrow[F].fromEither(Uri.fromString(uri))

  trait PartEncoder[-PartType] {
    def encode[F[_]](name: String, value: PartType): Part[F]
  }

  implicit object JsonPartEncoder extends PartEncoder[Json] {

    override def encode[F[_]](name: String, value: Json): Part[F] = Part
      .formData[F](name, encodeValue(value), contentType)

    private def encodeValue(value: Json): String = value.noSpaces

    private val contentType: `Content-Type` = `Content-Type`(MediaType.application.json)
  }

  implicit object StringPartEncoder extends PartEncoder[String] {

    override def encode[F[_]](name: String, value: String): Part[F] = Part
      .formData[F](name, encodeValue(value), contentType)

    private def encodeValue(value: String): String = value

    private val contentType: `Content-Type` = `Content-Type`(MediaType.text.plain)
  }

  implicit object ZipPartEncoder extends PartEncoder[ByteArrayTinyType with ZippedContent] {

    override def encode[F[_]](name: String, value: ByteArrayTinyType with ZippedContent): Part[F] = Part(
      Headers(
        `Content-Disposition`("form-data", Map(ci"name" -> name)),
        Header.Raw(ci"Content-Transfer-Encoding", "binary"),
        `Content-Type`(MediaType.application.zip)
      ),
      body = Stream.emits(value.value)
    )
  }
}
