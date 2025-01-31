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

package io.renku.eventlog.events.producers
package globalcommitsync

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all._
import io.renku.eventlog.InMemoryEventLogDbSpec
import io.renku.eventlog.events.producers.SubscriptionDataProvisioning
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.events.CategoryName
import io.renku.events.consumers.ConsumersModelGenerators.consumerProjects
import io.renku.events.consumers.Project
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.EventContentGenerators.eventDates
import io.renku.graph.model.EventsGenerators.lastSyncedDates
import io.renku.graph.model.GraphModelGenerators.projectIds
import io.renku.graph.model.events.LastSyncedDate
import io.renku.graph.model.projects
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import skunk._
import skunk.data.Completion
import skunk.implicits.toStringOps

class LastSyncedDateUpdaterSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with SubscriptionDataProvisioning
    with MockFactory
    with should.Matchers {

  "run" should {

    "delete the current last synced date if the argument is None" in new TestCase {
      val oldLastSyncedDate = lastSyncedDates.generateOne
      upsertCategorySyncTime(project.id, categoryName, oldLastSyncedDate)

      getLastSyncedDate(project) shouldBe oldLastSyncedDate.some

      updater.run(project.id, None).unsafeRunSync() shouldBe Completion.Delete(1)

      getLastSyncedDate(project) shouldBe None
    }

    "update the previous last synced date" in new TestCase {
      val oldLastSyncedDate = lastSyncedDates.generateOne
      upsertCategorySyncTime(project.id, categoryName, oldLastSyncedDate)

      getLastSyncedDate(project) shouldBe oldLastSyncedDate.some

      val newLastSyncedDate = lastSyncedDates.generateOne

      updater
        .run(project.id, newLastSyncedDate.some)
        .unsafeRunSync() shouldBe Completion.Insert(1)

      getLastSyncedDate(project) shouldBe newLastSyncedDate.some
    }

    "insert a new last synced date" in new TestCase {
      val newLastSyncedDate = lastSyncedDates.generateOne

      updater
        .run(project.id, newLastSyncedDate.some)
        .unsafeRunSync() shouldBe Completion.Insert(1)

      getLastSyncedDate(project) shouldBe newLastSyncedDate.some
    }

    "do nothing if project with the given id does not exist" in new TestCase {
      updater
        .run(projectIds.generateOne, lastSyncedDates.generateSome)
        .unsafeRunSync() shouldBe Completion.Insert(0)
    }
  }

  private trait TestCase {
    val project = consumerProjects.generateOne

    private implicit val metricsRegistry:  TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    private implicit val queriesExecTimes: QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val updater = new LastSyncedDateUpdateImpl[IO]

    upsertProject(project.id, project.slug, eventDates.generateOne)
  }

  private def getLastSyncedDate(project: Project): Option[LastSyncedDate] = execute {
    Kleisli { session =>
      val query: Query[projects.GitLabId *: CategoryName *: EmptyTuple, LastSyncedDate] =
        sql"""SELECT sync_time.last_synced FROM subscription_category_sync_time sync_time
                WHERE sync_time.project_id = $projectIdEncoder 
                AND sync_time.category_name = $categoryNameEncoder
                """
          .query(lastSyncedDateDecoder)

      session.prepare(query).flatMap(p => p.option(project.id *: categoryName *: EmptyTuple))
    }
  }
}
