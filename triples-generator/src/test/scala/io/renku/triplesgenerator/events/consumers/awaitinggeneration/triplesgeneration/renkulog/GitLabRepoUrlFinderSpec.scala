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

package io.renku.triplesgenerator.events.consumers.awaitinggeneration.triplesgeneration.renkulog

import Commands.{GitLabRepoUrlFinder, GitLabRepoUrlFinderImpl}
import cats.syntax.all._
import io.renku.config.ServiceUrl
import io.renku.generators.CommonGraphGenerators._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.GitLabUrl
import io.renku.graph.model.GraphModelGenerators._
import io.renku.http.client.AccessToken
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

class GitLabRepoUrlFinderSpec extends AnyWordSpec with MockFactory with should.Matchers with TableDrivenPropertyChecks {

  "findRepositoryUrl" should {

    protocols foreach { protocol =>
      val host = nonEmptyStrings().generateOne
      val port = positiveInts(9999).generateOne
      val slug = projectSlugs.generateOne

      s"return '$protocol://$host:$port/$slug.git' when no access token" in new TestCase {

        implicit val maybeAccessToken: Option[AccessToken] = Option.empty

        val repoUrlFinder = newRepoUrlFinder(GitLabUrl(s"$protocol://$host:$port"))

        repoUrlFinder.findRepositoryUrl(slug) shouldBe
          ServiceUrl(s"$protocol://$host:$port/$slug.git").pure[Try]
      }

      s"return '$protocol://gitlab-ci-token:<token>@$host:$port/$slug.git' for Personal Access Token" in new TestCase {

        val accessToken = personalAccessTokens.generateOne
        implicit val iat: Option[AccessToken] = accessToken.some

        val repoUrlFinder = newRepoUrlFinder(GitLabUrl(s"$protocol://$host:$port"))

        repoUrlFinder.findRepositoryUrl(slug) shouldBe
          ServiceUrl(s"$protocol://gitlab-ci-token:${accessToken.value}@$host:$port/$slug.git").pure[Try]
      }

      forAll {
        Table(
          "token type"              -> "token",
          "Project Access Token"    -> projectAccessTokens.generateOne,
          "User OAuth Access Token" -> userOAuthAccessTokens.generateOne
        )
      } { (tokenType, accessToken: AccessToken) =>
        s"return '$protocol://oauth2:<token>@$host:$port/$slug.git' for $tokenType" in new TestCase {

          implicit val someToken: Option[AccessToken] = accessToken.some

          val repoUrlFinder = newRepoUrlFinder(GitLabUrl(s"$protocol://$host:$port"))

          repoUrlFinder.findRepositoryUrl(slug) shouldBe
            ServiceUrl(s"$protocol://oauth2:${accessToken.value}@$host:$port/$slug.git").pure[Try]
        }
      }
    }
  }

  private trait TestCase {
    val newRepoUrlFinder: GitLabUrl => GitLabRepoUrlFinder[Try] = new GitLabRepoUrlFinderImpl[Try](_)
  }

  private lazy val protocols = Set("http", "https")
}
