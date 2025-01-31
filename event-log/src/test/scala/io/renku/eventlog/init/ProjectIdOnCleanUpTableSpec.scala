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

package io.renku.eventlog.init

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all._
import io.renku.eventlog.TypeSerializers
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.projects
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Info
import io.renku.testtools.IOSpec
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import skunk._
import skunk.implicits._

class ProjectIdOnCleanUpTableSpec
    extends AnyWordSpec
    with IOSpec
    with DbInitSpec
    with should.Matchers
    with TypeSerializers {

  protected[init] override lazy val migrationsToRun: List[DbMigrator[IO]] = allMigrations.takeWhile {
    case _: ProjectIdOnCleanUpTableImpl[IO] => false
    case _ => true
  }

  "run" should {

    "add 'project_id' column to the 'clean_up_events_queue' if doesn't exist" in new TestCase {

      verifyColumnExists("clean_up_events_queue", "project_id") shouldBe false

      migrator.run.unsafeRunSync() shouldBe ()

      verifyColumnExists("clean_up_events_queue", "project_id") shouldBe true

      logger.loggedOnly(Info("'clean_up_events_queue.project_id' column added"))

      logger.reset()

      migrator.run.unsafeRunSync() shouldBe ()

      logger.loggedOnly(Info("'clean_up_events_queue.project_id' column exists"))
    }

    "fill in the new 'project_id' column with data from the 'project' table " +
      "and remove rows without matching project" in new TestCase {

        val projectSlug1 = projectSlugs.generateOne
        insertToQueue(projectSlug1)
        val projectId1 = projectIds.generateOne
        insertToProject(projectSlug1, projectId1)

        val projectSlug2 = projectSlugs.generateOne
        insertToQueue(projectSlug2)

        migrator.run.unsafeRunSync() shouldBe ()

        findQueueRows shouldBe List(projectSlug1 -> projectId1)
      }
  }

  private trait TestCase {
    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val migrator = new ProjectIdOnCleanUpTableImpl[IO]
  }

  private def insertToQueue(slug: projects.Slug): Unit = executeCommand {
    sql"""INSERT INTO clean_up_events_queue(date, project_path)
          VALUES(now(), '#${slug.show}')
       """.command
  }

  private def insertToProject(slug: projects.Slug, id: projects.GitLabId): Unit = executeCommand {
    sql"""INSERT INTO project(project_id, project_path, latest_event_date)
          VALUES (#${id.show}, '#${slug.show}', now())
       """.command
  }

  private def findQueueRows: List[(projects.Slug, projects.GitLabId)] =
    execute[List[(projects.Slug, projects.GitLabId)]] {
      Kleisli { session =>
        val query: Query[Void, projects.Slug ~ projects.GitLabId] = sql"""
          SELECT project_path, project_id 
          FROM clean_up_events_queue"""
          .query(projectSlugDecoder ~ projectIdDecoder)
          .map { case (slug: projects.Slug, id: projects.GitLabId) => slug -> id }
        session.execute(query)
      }
    }
}
