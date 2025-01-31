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

import cats.effect._
import io.renku.interpreters.TestLogger.Level.Info
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class ProjectsTokensTableCreatorSpec
    extends AnyWordSpec
    with IOSpec
    with DbInitSpec
    with should.Matchers
    with MockFactory {

  protected override val migrationsToRun: List[DBMigration[IO]] = List.empty

  "run" should {

    "create the projects_tokens table if id does not exist" in new TestCase {
      tableExists("projects_tokens") shouldBe false

      tableCreator.run.unsafeRunSync() shouldBe ()

      logger.loggedOnly(Info("'projects_tokens' table created"))

      tableExists("projects_tokens") shouldBe true

      tableCreator.run.unsafeRunSync() shouldBe ()

      logger.loggedOnly(Info("'projects_tokens' table created"), Info("'projects_tokens' table existed"))
    }
  }

  private trait TestCase {
    val tableCreator = new ProjectsTokensTableCreator[IO]
  }
}
