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

package io.renku.http.rest.paging

import cats.syntax.all._
import io.renku.http.rest.paging.model._
import org.http4s.Query

final case class PagingRequest(page: Page, perPage: PerPage)

object PagingRequest {
  import cats.data._
  import org.http4s.dsl.impl.OptionalValidatingQueryParamDecoderMatcher
  import org.http4s.{ParseFailure, QueryParamDecoder}

  val default: PagingRequest = PagingRequest(Page.first, PerPage.default)

  def apply(maybePage:    Option[ValidatedNel[ParseFailure, Page]],
            maybePerPage: Option[ValidatedNel[ParseFailure, PerPage]]
  ): ValidatedNel[ParseFailure, PagingRequest] =
    (maybePage getOrElse Page.first.validNel, maybePerPage getOrElse PerPage.default.validNel)
      .mapN(PagingRequest.apply)

  object Decoders {
    private implicit val pageParameterDecoder: QueryParamDecoder[Page] =
      value =>
        Either
          .catchOnly[NumberFormatException](value.value.toInt)
          .flatMap(Page.from)
          .leftMap(_ => new IllegalArgumentException(page.errorMessage(value.value)))
          .leftMap(_.getMessage)
          .leftMap(ParseFailure(_, ""))
          .toValidatedNel

    object page extends OptionalValidatingQueryParamDecoderMatcher[Page]("page") {
      val parameterName: String = "page"
      def errorMessage(value: String): String = s"'$value' not a valid '$parameterName' value"
      def find(query: Query): Option[ValidatedNel[ParseFailure, Page]] = page.unapply(query.multiParams).flatten
    }

    private implicit val perPageParameterDecoder: QueryParamDecoder[PerPage] =
      value =>
        value.value.toIntOption match {
          case None                  => ParseFailure(perPage.errorMessage(value.value), "").invalidNel[PerPage]
          case Some(int) if int <= 0 => ParseFailure(perPage.errorMessage(value.value), "").invalidNel[PerPage]
          case Some(int) if int > PerPage.max.value =>
            ParseFailure(s"'$int' not a valid 'per_page' value. Max value is ${PerPage.max}", "").invalidNel[PerPage]
          case Some(int) =>
            PerPage.from(int).leftMap(_ => ParseFailure(perPage.errorMessage(value.value), "")).toValidatedNel
        }

    object perPage extends OptionalValidatingQueryParamDecoderMatcher[PerPage]("per_page") {
      val parameterName: String = "per_page"
      def errorMessage(value: String): String = s"'$value' not a valid '$parameterName' value"
      def find(query: Query): Option[ValidatedNel[ParseFailure, PerPage]] = perPage.unapply(query.multiParams).flatten
    }
  }
}
