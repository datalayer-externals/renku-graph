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

import cats.Show
import org.http4s.Status

sealed trait RestClientError extends Exception

object RestClientError {

  final case class UnexpectedResponseException(status: Status, message: String)
      extends Exception(message)
      with RestClientError

  final case class BadRequestException(message: String) extends Exception(message) with RestClientError

  final case class ConnectivityException(message: String, cause: Throwable)
      extends Exception(message, cause)
      with RestClientError

  final case class ClientException(message: String, cause: Throwable)
      extends Exception(message, cause)
      with RestClientError

  final case class MappingException(message: String, cause: Throwable)
      extends Exception(message, cause)
      with RestClientError

  final case object UnauthorizedException extends RuntimeException("Unauthorized") with RestClientError {
    implicit val exceptionShow: Show[UnauthorizedException] = Show.show(_.getMessage)
  }
  type UnauthorizedException = UnauthorizedException.type
}
