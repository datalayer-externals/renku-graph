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
import io.circe.literal._
import io.renku.eventlog.init.Generators.events
import io.renku.eventlog.init.model.Event
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.EventContentGenerators._
import io.renku.graph.model.EventsGenerators._
import io.renku.graph.model.events._
import io.renku.graph.model.projects
import io.renku.graph.model.projects.Slug
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Info
import io.renku.testtools.IOSpec
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import skunk._
import skunk.codec.all._
import skunk.implicits._

class ProjectPathAdderSpec
    extends AnyWordSpec
    with IOSpec
    with DbInitSpec
    with should.Matchers
    with Eventually
    with IntegrationPatience {

  protected[init] override lazy val migrationsToRun: List[DbMigrator[IO]] = allMigrations.takeWhile {
    case _: ProjectPathAdderImpl[IO] => false
    case _ => true
  }

  "run" should {

    "do nothing if the 'event' table already exists" in new TestCase {

      createEventTable()

      projectPathAdder.run.unsafeRunSync() shouldBe ()

      logger.loggedOnly(Info("'project_path' column adding skipped"))
    }

    "do nothing if the 'project_path' column already exists" in new TestCase {

      verifyColumnExists("event_log", "project_path") shouldBe false

      projectPathAdder.run.unsafeRunSync() shouldBe ()

      verifyColumnExists("event_log", "project_path") shouldBe true

      logger.loggedOnly(Info("'project_path' column added"))

      logger.reset()

      projectPathAdder.run.unsafeRunSync() shouldBe ()

      logger.loggedOnly(Info("'project_path' column exists"))
    }

    "add the 'project_path' column if does not exist and migrate the data for it" in new TestCase {

      verifyColumnExists("event_log", "project_path") shouldBe false

      val event1 = events.generateOne
      storeEvent(event1)
      val event2 = events.generateOne
      storeEvent(event2)

      projectPathAdder.run.unsafeRunSync() shouldBe ()

      findProjectSlugs shouldBe Set(event1.project.slug, event2.project.slug)

      verifyIndexExists("event_log", "idx_project_path") shouldBe true

      eventually {
        logger.loggedOnly(Info("'project_path' column added"))
      }
    }
  }

  private trait TestCase {
    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val projectPathAdder = new ProjectPathAdderImpl[IO]
  }

  private def storeEvent(event: Event): Unit = execute[Unit] {
    Kleisli { session =>
      val query: Command[
        EventId *: projects.GitLabId *: EventStatus *: CreatedDate *: ExecutionDate *: EventDate *: String *: EmptyTuple
      ] =
        sql"""insert into
            event_log (event_id, project_id, status, created_date, execution_date, event_date, event_body) 
            values (
            $eventIdEncoder, 
            $projectIdEncoder, 
            $eventStatusEncoder, 
            $createdDateEncoder, 
            $executionDateEncoder, 
            $eventDateEncoder, 
            $text)
        """.command

      session
        .prepare(query)
        .flatMap(
          _.execute(
            event.id *:
              event.project.id *:
              eventStatuses.generateOne *:
              createdDates.generateOne *:
              executionDates.generateOne *:
              eventDates.generateOne *:
              toJson(event) *:
              EmptyTuple
          )
        )
        .void
    }
  }

  private def toJson(event: Event): String = json"""{
    "project": {
      "id":   ${event.project.id},
      "slug": ${event.project.slug}
    }
  }""".noSpaces

  private def findProjectSlugs: Set[Slug] = sessionResource
    .useK {
      Kleisli { session =>
        val query: Query[Void, projects.Slug] = sql"select project_path from event_log".query(projectSlugDecoder)
        session.execute(query)
      }
    }
    .unsafeRunSync()
    .toSet
}
