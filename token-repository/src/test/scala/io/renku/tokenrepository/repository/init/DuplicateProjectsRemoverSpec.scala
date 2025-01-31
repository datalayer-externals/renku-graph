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

package io.renku.tokenrepository.repository.init

import cats.data.Kleisli
import cats.effect.IO
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.projects
import io.renku.graph.model.projects.{GitLabId, Slug}
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Info
import io.renku.testtools.IOSpec
import io.renku.tokenrepository.repository.AccessTokenCrypto.EncryptedAccessToken
import io.renku.tokenrepository.repository.RepositoryGenerators.encryptedAccessTokens
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import skunk._
import skunk.codec.all.{int4, varchar}
import skunk.implicits._

class DuplicateProjectsRemoverSpec
    extends AnyWordSpec
    with IOSpec
    with DbInitSpec
    with should.Matchers
    with MockFactory {

  protected override lazy val migrationsToRun: List[DBMigration[IO]] = allMigrations.takeWhile {
    case _: DuplicateProjectsRemover[IO] => false
    case _ => true
  }

  "run" should {

    "de-duplicate rows with the same project_path but different ids" in new TestCase {

      val projectSlug     = projectSlugs.generateOne
      val projectId1      = projects.GitLabId(1)
      val encryptedToken1 = encryptedAccessTokens.generateOne
      insert(projectId1, projectSlug, encryptedToken1)
      val projectId2      = projects.GitLabId(2)
      val encryptedToken2 = encryptedAccessTokens.generateOne
      insert(projectId2, projectSlug, encryptedToken2)
      val projectSlug3    = projectSlugs.generateOne
      val encryptedToken3 = encryptedAccessTokens.generateOne
      insert(projects.GitLabId(3), projectSlug3, encryptedToken3)

      findToken(projectId1) shouldBe Some(encryptedToken1.value)
      findToken(projectId2) shouldBe Some(encryptedToken2.value)

      deduplicator.run.unsafeRunSync() shouldBe ((): Unit)

      findToken(projectId1)   shouldBe None
      findToken(projectId2)   shouldBe Some(encryptedToken2.value)
      findToken(projectSlug)  shouldBe Some(encryptedToken2.value)
      findToken(projectSlug3) shouldBe Some(encryptedToken3.value)

      logger.loggedOnly(Info("Projects de-duplicated"))
    }
  }

  private trait TestCase {
    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val deduplicator = new DuplicateProjectsRemover[IO]
  }

  protected def insert(projectId: GitLabId, projectSlug: Slug, encryptedToken: EncryptedAccessToken): Unit =
    execute {
      Kleisli[IO, Session[IO], Unit] { session =>
        val query: Command[Int *: String *: String *: EmptyTuple] =
          sql"""insert into
                projects_tokens (project_id, project_path, token)
                values ($int4, $varchar, $varchar)
         """.command
        session
          .prepare(query)
          .flatMap(_.execute(projectId.value *: projectSlug.value *: encryptedToken.value *: EmptyTuple))
          .map(assureInserted)
      }
    }
}
