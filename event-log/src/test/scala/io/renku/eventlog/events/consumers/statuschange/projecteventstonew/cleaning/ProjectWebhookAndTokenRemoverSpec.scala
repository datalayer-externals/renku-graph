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

package io.renku.eventlog.events.consumers.statuschange.projecteventstonew.cleaning

import cats.effect.IO
import cats.syntax.all._
import com.github.tomakehurst.wiremock.client.WireMock._
import io.renku.events.consumers.ConsumersModelGenerators._
import io.renku.eventlog.events.consumers.statuschange.categoryName
import io.renku.generators.CommonGraphGenerators.accessTokens
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.exceptions
import io.renku.graph.model.projects
import io.renku.graph.tokenrepository.{AccessTokenFinder, TokenRepositoryUrl}
import io.renku.graph.webhookservice.WebhookServiceUrl
import io.renku.http.client.AccessToken
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Warn
import io.renku.stubbing.ExternalServiceStubbing
import io.renku.testtools.IOSpec
import org.http4s.Status
import org.http4s.Status.{Forbidden, InternalServerError, NotFound, ServiceUnavailable, Unauthorized}
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class ProjectWebhookAndTokenRemoverSpec
    extends AnyWordSpec
    with ExternalServiceStubbing
    with IOSpec
    with MockFactory
    with should.Matchers {

  "removeWebhookAndToken" should {

    "remove the token and the webhook of the specified project" in new TestCase {
      (accessTokenFinder
        .findAccessToken[projects.GitLabId](_: projects.GitLabId)(_: projects.GitLabId => String))
        .expects(project.id, AccessTokenFinder.Implicits.projectIdToPath)
        .returns(accessToken.some.pure[IO])
      stubFor {
        delete(s"/projects/${project.id}/webhooks")
          .withAccessToken(accessToken.some)
          .willReturn(ok())
      }
      stubFor {
        delete(s"/projects/${project.id}/tokens")
          .willReturn(noContent())
      }

      webhookAndTokenRemover.removeWebhookAndToken(project).unsafeRunSync() shouldBe ()
    }

    NotFound :: Unauthorized :: Forbidden :: Nil foreach { status =>
      s"remove the token even if the webhook removal returned $status" in new TestCase {
        (accessTokenFinder
          .findAccessToken[projects.GitLabId](_: projects.GitLabId)(_: projects.GitLabId => String))
          .expects(project.id, AccessTokenFinder.Implicits.projectIdToPath)
          .returns(accessToken.some.pure[IO])
        stubFor {
          delete(s"/projects/${project.id}/webhooks")
            .withAccessToken(accessToken.some)
            .willReturn(aResponse().withStatus(status.code))
        }
        stubFor {
          delete(s"/projects/${project.id}/tokens")
            .willReturn(noContent())
        }

        webhookAndTokenRemover.removeWebhookAndToken(project).unsafeRunSync() shouldBe ()

        logger.expectNoLogs()
      }
    }

    "do nothing when the tokenFinder returns no token for the project" in new TestCase {
      (accessTokenFinder
        .findAccessToken[projects.GitLabId](_: projects.GitLabId)(_: projects.GitLabId => String))
        .expects(project.id, AccessTokenFinder.Implicits.projectIdToPath)
        .returns(None.pure[IO])

      webhookAndTokenRemover.removeWebhookAndToken(project).unsafeRunSync() shouldBe ()
    }

    "fail when the tokenFinder fails" in new TestCase {
      val exception = exceptions.generateOne
      (accessTokenFinder
        .findAccessToken[projects.GitLabId](_: projects.GitLabId)(_: projects.GitLabId => String))
        .expects(project.id, AccessTokenFinder.Implicits.projectIdToPath)
        .returns(exception.raiseError[IO, Option[AccessToken]])
      intercept[Exception] {
        webhookAndTokenRemover.removeWebhookAndToken(project).unsafeRunSync()
      }.getMessage shouldBe exception.getMessage
    }

    s"do nothing when the webhook deletion ends with $InternalServerError" in new TestCase {
      (accessTokenFinder
        .findAccessToken[projects.GitLabId](_: projects.GitLabId)(_: projects.GitLabId => String))
        .expects(project.id, AccessTokenFinder.Implicits.projectIdToPath)
        .returns(accessToken.some.pure[IO])
      stubFor {
        delete(s"/projects/${project.id}/webhooks")
          .withAccessToken(accessToken.some)
          .willReturn(aResponse().withStatus(InternalServerError.code))
      }

      webhookAndTokenRemover.removeWebhookAndToken(project).unsafeRunSync() shouldBe ()

      logger.loggedOnly(Warn(show"$categoryName: removing webhook for project: $project got $InternalServerError"))
    }

    "fail when the webhook deletion fails" in new TestCase {
      (accessTokenFinder
        .findAccessToken[projects.GitLabId](_: projects.GitLabId)(_: projects.GitLabId => String))
        .expects(project.id, AccessTokenFinder.Implicits.projectIdToPath)
        .returns(accessToken.some.pure[IO])
      stubFor {
        delete(s"/projects/${project.id}/webhooks")
          .withAccessToken(accessToken.some)
          .willReturn(serviceUnavailable())
      }
      intercept[Exception] {
        webhookAndTokenRemover.removeWebhookAndToken(project).unsafeRunSync()
      }.getMessage shouldBe s"DELETE $webhookServiceUrl/projects/${project.id}/webhooks returned $ServiceUnavailable; error: removing webhook failed with status: $ServiceUnavailable for project: ${project.show}"
    }

    "fail when the token deletion fails" in new TestCase {
      (accessTokenFinder
        .findAccessToken[projects.GitLabId](_: projects.GitLabId)(_: projects.GitLabId => String))
        .expects(project.id, AccessTokenFinder.Implicits.projectIdToPath)
        .returns(accessToken.some.pure[IO])
      stubFor {
        delete(s"/projects/${project.id}/webhooks")
          .withAccessToken(accessToken.some)
          .willReturn(ok())
      }
      stubFor {
        delete(s"/projects/${project.id}/tokens")
          .willReturn(serverError())
      }
      intercept[Exception] {
        webhookAndTokenRemover.removeWebhookAndToken(project).unsafeRunSync()
      }.getMessage shouldBe s"DELETE $tokenRepositoryUrl/projects/${project.id}/tokens returned ${Status.InternalServerError}; error: Removing project token failed with status: $InternalServerError for project: ${project.show}"
    }
  }

  private trait TestCase {
    implicit val accessToken: AccessToken = accessTokens.generateOne
    val project = consumerProjects.generateOne

    implicit val logger:            TestLogger[IO]        = TestLogger[IO]()
    implicit val accessTokenFinder: AccessTokenFinder[IO] = mock[AccessTokenFinder[IO]]
    val tokenRepositoryUrl     = TokenRepositoryUrl(externalServiceBaseUrl)
    val webhookServiceUrl      = WebhookServiceUrl(externalServiceBaseUrl)
    val webhookAndTokenRemover = new ProjectWebhookAndTokenRemoverImpl[IO](webhookServiceUrl, tokenRepositoryUrl)
  }
}
