/*
 * Copyright 2021 Swiss Data Science Center (SDSC)
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

package io.renku.webhookservice.hookvalidation

import cats.effect.IO
import cats.syntax.all._
import io.circe.Json
import io.circe.literal._
import io.circe.syntax._
import io.renku.generators.CommonGraphGenerators.authUsers
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators.projectIds
import io.renku.graph.model.projects.Id
import io.renku.http.ErrorMessage
import io.renku.http.ErrorMessage._
import io.renku.http.client.AccessToken
import io.renku.http.client.RestClientError.UnauthorizedException
import io.renku.http.server.EndpointTester._
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Error
import io.renku.testtools.IOSpec
import io.renku.webhookservice.hookvalidation.HookValidator.HookValidationResult.{HookExists, HookMissing}
import org.http4s.Status._
import org.http4s._
import org.http4s.headers.`Content-Type`
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class HookValidationEndpointSpec extends AnyWordSpec with MockFactory with should.Matchers with IOSpec {

  "validateHook" should {

    "return OK when the hook exists for the project with the given id" in new TestCase {

      (hookValidator
        .validateHook(_: Id, _: Option[AccessToken]))
        .expects(projectId, Some(authUser.accessToken))
        .returning(HookExists.pure[IO])

      val response = validateHook(projectId, authUser).unsafeRunSync()

      response.status                   shouldBe Ok
      response.contentType              shouldBe Some(`Content-Type`(MediaType.application.json))
      response.as[Json].unsafeRunSync() shouldBe json"""{"message": "Hook valid"}"""
    }

    "return NOT_FOUND the hook does not exist" in new TestCase {

      (hookValidator
        .validateHook(_: Id, _: Option[AccessToken]))
        .expects(projectId, Some(authUser.accessToken))
        .returning(HookMissing.pure[IO])

      val response = validateHook(projectId, authUser).unsafeRunSync()

      response.status                   shouldBe NotFound
      response.contentType              shouldBe Some(`Content-Type`(MediaType.application.json))
      response.as[Json].unsafeRunSync() shouldBe json"""{"message": "Hook not found"}"""
    }

    "return INTERNAL_SERVER_ERROR when there was an error during hook validation and log the error" in new TestCase {

      val errorMessage      = ErrorMessage("some error")
      private val exception = new Exception(errorMessage.toString())
      (hookValidator
        .validateHook(_: Id, _: Option[AccessToken]))
        .expects(projectId, Some(authUser.accessToken))
        .returning(IO.raiseError(exception))

      val response = validateHook(projectId, authUser).unsafeRunSync()

      response.status                   shouldBe InternalServerError
      response.contentType              shouldBe Some(`Content-Type`(MediaType.application.json))
      response.as[Json].unsafeRunSync() shouldBe errorMessage.asJson

      logger.loggedOnly(
        Error(exception.getMessage, exception)
      )
    }

    "return UNAUTHORIZED when there was an UnauthorizedException thrown during hook validation" in new TestCase {

      (hookValidator
        .validateHook(_: Id, _: Option[AccessToken]))
        .expects(projectId, Some(authUser.accessToken))
        .returning(IO.raiseError(UnauthorizedException))

      val response = validateHook(projectId, authUser).unsafeRunSync()

      response.status                   shouldBe Unauthorized
      response.contentType              shouldBe Some(`Content-Type`(MediaType.application.json))
      response.as[Json].unsafeRunSync() shouldBe ErrorMessage(UnauthorizedException).asJson
    }
  }

  private trait TestCase {

    val projectId = projectIds.generateOne
    val authUser  = authUsers.generateOne

    implicit val logger = TestLogger[IO]()
    val hookValidator   = mock[HookValidator[IO]]
    val validateHook    = new HookValidationEndpointImpl[IO](hookValidator).validateHook _
  }
}
