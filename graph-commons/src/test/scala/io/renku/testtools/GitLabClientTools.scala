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

package io.renku.testtools

import cats.Applicative
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import io.circe.Json
import io.renku.generators.Generators.Implicits._
import io.renku.http.client.RestClient.ResponseMappingF
import io.renku.http.client.{AccessToken, GitLabClient}
import org.http4s.Uri
import org.http4s.multipart.Multipart
import org.scalacheck.Gen
import org.scalamock.clazz.Mock
import org.scalamock.function.MockFunctions
import org.scalamock.matchers.ArgCapture.CaptureOne
import org.scalamock.matchers.{Matchers, MockParameter}

trait GitLabClientTools[F[_]] {
  self: Mock with MockFunctions with Matchers =>

  sealed trait UnderlyingMethod
  case object Get           extends UnderlyingMethod
  case object Head          extends UnderlyingMethod
  case object PostJson      extends UnderlyingMethod
  case object PostMultipart extends UnderlyingMethod
  case object Put           extends UnderlyingMethod
  case object Delete        extends UnderlyingMethod

  def captureMapping[ResultType](gitLabClient: GitLabClient[F])(
      findingMethod:         => Any,
      resultGenerator:       Gen[ResultType],
      underlyingMethod:      UnderlyingMethod,
      maybeEndpointName:     Option[String Refined NonEmpty] = None,
      expectedNumberOfCalls: Int = 1
  )(implicit applicative: Applicative[F]): ResponseMappingF[F, ResultType] = {
    val responseMapping = CaptureOne[ResponseMappingF[F, ResultType]]()

    val endpointName: MockParameter[String Refined NonEmpty] =
      maybeEndpointName.map(new MockParameter(_)).getOrElse(*)

    underlyingMethod match {
      case Get =>
        (gitLabClient
          .get(_: Uri, _: String Refined NonEmpty)(_: ResponseMappingF[F, ResultType])(
            _: Option[AccessToken]
          ))
          .expects(*, endpointName, capture(responseMapping), *)
          .returning(resultGenerator.generateOne.pure[F])
          .repeat(expectedNumberOfCalls)
      case Head =>
        (gitLabClient
          .head(_: Uri, _: String Refined NonEmpty)(_: ResponseMappingF[F, ResultType])(
            _: Option[AccessToken]
          ))
          .expects(*, endpointName, capture(responseMapping), *)
          .returning(resultGenerator.generateOne.pure[F])
          .repeat(expectedNumberOfCalls)
      case PostJson =>
        (gitLabClient
          .post(_: Uri, _: String Refined NonEmpty, _: Json)(_: ResponseMappingF[F, ResultType])(
            _: Option[AccessToken]
          ))
          .expects(*, endpointName, *, capture(responseMapping), *)
          .returning(resultGenerator.generateOne.pure[F])
          .repeat(expectedNumberOfCalls)
      case PostMultipart =>
        (gitLabClient
          .postMultipart(_: Uri, _: String Refined NonEmpty, _: Multipart[F])(_: ResponseMappingF[F, ResultType])(
            _: AccessToken
          ))
          .expects(*, endpointName, *, capture(responseMapping), *)
          .returning(resultGenerator.generateOne.pure[F])
          .repeat(expectedNumberOfCalls)
      case Put =>
        (gitLabClient
          .put(_: Uri, _: String Refined NonEmpty, _: Multipart[F])(_: ResponseMappingF[F, ResultType])(
            _: Option[AccessToken]
          ))
          .expects(*, endpointName, *, capture(responseMapping), *)
          .returning(resultGenerator.generateOne.pure[F])
          .repeat(expectedNumberOfCalls)
      case Delete =>
        (gitLabClient
          .delete(_: Uri, _: String Refined NonEmpty)(_: ResponseMappingF[F, ResultType])(
            _: Option[AccessToken]
          ))
          .expects(*, endpointName, capture(responseMapping), *)
          .returning(resultGenerator.generateOne.pure[F])
          .repeat(expectedNumberOfCalls)
    }

    findingMethod
    responseMapping.value
  }
}
