/*
 * Copyright 2022 Swiss Data Science Center (SDSC)
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

package io.renku.triplesgenerator.events.categories.triplesgenerated

import cats.MonadThrow
import cats.syntax.all._
import io.renku.http.client.RestClientError._
import io.renku.triplesgenerator.events.categories.ProcessingNonRecoverableError._
import io.renku.triplesgenerator.events.categories.ProcessingRecoverableError
import io.renku.triplesgenerator.events.categories.ProcessingRecoverableError._
import org.http4s.Status.{Forbidden, InternalServerError, Unauthorized}

private trait RecoverableErrorsRecovery {

  private type RecoveryStrategy[F[_], OUT] = PartialFunction[Throwable, F[Either[ProcessingRecoverableError, OUT]]]

  def maybeRecoverableError[F[_]: MonadThrow, OUT]: RecoveryStrategy[F, OUT] = maybeRecoverableError(None)
  def maybeRecoverableError[F[_]: MonadThrow, OUT](message: String): RecoveryStrategy[F, OUT] = maybeRecoverableError(
    Some(message)
  )

  def maybeRecoverableError[F[_]: MonadThrow, OUT](maybeMessage: Option[String]): RecoveryStrategy[F, OUT] = {
    case exception @ (_: ConnectivityException | _: ClientException) =>
      LogWorthyRecoverableError(composeFullMessage(maybeMessage, exception), exception.getCause)
        .asLeft[OUT]
        .leftWiden[ProcessingRecoverableError]
        .pure[F]
    case exception @ UnexpectedResponseException(Unauthorized | Forbidden, _) =>
      SilentRecoverableError(composeFullMessage(maybeMessage, exception), exception.getCause)
        .asLeft[OUT]
        .leftWiden[ProcessingRecoverableError]
        .pure[F]
    case exception @ UnexpectedResponseException(InternalServerError, _) =>
      MalformedRepository(composeFullMessage(maybeMessage, exception), exception.getCause)
        .raiseError[F, Either[ProcessingRecoverableError, OUT]]
    case exception @ UnexpectedResponseException(_, _) =>
      LogWorthyRecoverableError(composeFullMessage(maybeMessage, exception), exception.getCause)
        .asLeft[OUT]
        .leftWiden[ProcessingRecoverableError]
        .pure[F]
    case exception @ UnauthorizedException =>
      SilentRecoverableError(composeFullMessage(maybeMessage, exception), exception.getCause)
        .asLeft[OUT]
        .leftWiden[ProcessingRecoverableError]
        .pure[F]
  }

  private def composeFullMessage(maybeMessage: Option[String], exception: Throwable) =
    maybeMessage.map(message => s"$message: ${exception.getMessage}").getOrElse(exception.getMessage)
}

private object RecoverableErrorsRecovery extends RecoverableErrorsRecovery
