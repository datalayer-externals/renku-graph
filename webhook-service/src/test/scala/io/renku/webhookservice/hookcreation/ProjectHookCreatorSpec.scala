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

package io.renku.webhookservice.hookcreation

import cats.effect.IO
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.collection.NonEmpty
import io.circe.Json
import io.renku.generators.CommonGraphGenerators._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.nonEmptyStrings
import io.renku.graph.model.GraphModelGenerators._
import io.renku.http.client.RestClient.ResponseMappingF
import io.renku.http.client.RestClientError.UnauthorizedException
import io.renku.http.client.{AccessToken, GitLabClient}
import io.renku.interpreters.TestLogger
import io.renku.stubbing.ExternalServiceStubbing
import io.renku.testtools.{GitLabClientTools, IOSpec}
import io.renku.webhookservice.WebhookServiceGenerators.{projectHookUrls, serializedHookTokens}
import io.renku.webhookservice.hookcreation.ProjectHookCreator.ProjectHook
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Request, Response, Status, Uri}
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class ProjectHookCreatorSpec
    extends AnyWordSpec
    with MockFactory
    with ExternalServiceStubbing
    with should.Matchers
    with GitLabClientTools[IO]
    with IOSpec {

  "create" should {

    "send relevant Json payload " +
      "and return Unit if the remote responds with CREATED" in new TestCase {

        (gitLabClient
          .post(_: Uri, _: NES, _: Json)(_: ResponseMappingF[IO, Unit])(_: Option[AccessToken]))
          .expects(uri, endpointName, toJson(projectHook), *, accessToken.some)
          .returning(().pure[IO])

        hookCreator.create(projectHook, accessToken).unsafeRunSync() shouldBe (): Unit
      }

    "succeeds when gitLabClient returns Created" in new TestCase {
      mapResponse(Status.Created, Request(), Response()).unsafeRunSync() shouldBe (): Unit
    }

    "return an UnauthorizedException if remote client responds with UNAUTHORIZED" in new TestCase {
      intercept[Exception] {
        mapResponse(Status.Unauthorized, Request(), Response()).unsafeRunSync()
      } shouldBe UnauthorizedException
    }

    "return an Exception with a hint for solving the problem if remote responds with UNPROCESSABLE_ENTITY (422)" in new TestCase {

      val code    = Status.UnprocessableEntity
      val message = nonEmptyStrings().generateOne

      val exception = intercept[Exception] {
        mapResponse(code, Request(), Response().withEntity(message)).unsafeRunSync()
      }

      exception.getMessage should include(s"$code, $message")
      exception.getMessage should include(
        "Check in GitLab settings to allow requests to the local network from web hooks and services"
      )
    }

    "return an Exception with a generic message " +
      "if remote responds with status other than CREATED, UNAUTHORIZED or UNPROCESSABLE_ENTITY" in new TestCase {

        val code    = Status.ImATeapot
        val message = nonEmptyStrings().generateOne

        val exception = intercept[Exception] {
          mapResponse(code, Request(), Response().withEntity(message)).unsafeRunSync()
        }

        exception.getMessage should include(s"$code, $message")
      }
  }

  private trait TestCase {
    type NES = String Refined NonEmpty
    val projectHook = projectHooks.generateOne
    val uri         = uri"projects" / projectHook.projectId.show / "hooks"
    val endpointName: NES = "create-hook"

    val accessToken = accessTokens.generateOne

    def toJson(projectHook: ProjectHook) =
      Json
        .obj(
          "id"          -> Json.fromInt(projectHook.projectId.value),
          "url"         -> Json.fromString(projectHook.projectHookUrl.value),
          "push_events" -> Json.fromBoolean(true),
          "token"       -> Json.fromString(projectHook.serializedHookToken.value)
        )

    implicit val logger:       TestLogger[IO]   = TestLogger[IO]()
    implicit val gitLabClient: GitLabClient[IO] = mock[GitLabClient[IO]]
    val hookCreator = new ProjectHookCreatorImpl[IO]

    lazy val mapResponse = captureMapping(gitLabClient)(
      hookCreator.create(projectHook, accessToken).unsafeRunSync(),
      resultGenerator = Gen.const(()),
      underlyingMethod = PostJson
    )
  }

  private implicit lazy val projectHooks: Gen[ProjectHook] = for {
    projectId           <- projectIds
    hookUrl             <- projectHookUrls
    serializedHookToken <- serializedHookTokens
  } yield ProjectHook(projectId, hookUrl, serializedHookToken)
}
