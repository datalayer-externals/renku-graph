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

package io.renku.eventlog.events.producers.projectsync

import cats.effect.IO
import io.renku.eventlog.InMemoryEventLogDbSpec
import io.renku.eventlog.events.producers.SubscriptionDataProvisioning
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.EventContentGenerators._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.events.LastSyncedDate
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.time.temporal.ChronoUnit.MICROS
import java.time.{Duration, Instant}

class EventFinderSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with SubscriptionDataProvisioning
    with MockFactory
    with should.Matchers {

  "popEvent" should {

    s"return an event for a project that has no row with the $categoryName in the subscription_category_sync_times table" in new TestCase {

      finder.popEvent().unsafeRunSync() shouldBe None

      val projectId   = projectIds.generateOne
      val projectSlug = projectSlugs.generateOne
      upsertProject(projectId, projectSlug, eventDates.generateOne)

      findProjectCategorySyncTimes(projectId) shouldBe Nil

      finder.popEvent().unsafeRunSync() shouldBe Some(ProjectSyncEvent(projectId, projectSlug))

      findProjectCategorySyncTimes(projectId) shouldBe List(
        categoryName -> LastSyncedDate(currentTime.truncatedTo(MICROS))
      )

      finder.popEvent().unsafeRunSync() shouldBe None
    }

    s"return an event for a project that has a row with the $categoryName in the subscription_category_sync_times table " +
      "with the last_synced > 24h " in new TestCase {

        val projectId   = projectIds.generateOne
        val projectSlug = projectSlugs.generateOne
        upsertProject(projectId, projectSlug, eventDates.generateOne)
        val lastSyncDate = relativeTimestamps(moreThanAgo = Duration.ofMinutes(24 * 60 + 1)).generateAs(LastSyncedDate)
        upsertCategorySyncTime(projectId, categoryName, lastSyncDate)

        findProjectCategorySyncTimes(projectId) shouldBe List(categoryName -> lastSyncDate)

        finder.popEvent().unsafeRunSync() shouldBe Some(ProjectSyncEvent(projectId, projectSlug))

        findProjectCategorySyncTimes(projectId) shouldBe List(
          categoryName -> LastSyncedDate(currentTime.truncatedTo(MICROS))
        )

        finder.popEvent().unsafeRunSync() shouldBe None
      }

    "return events ordered by the subscription_category_sync_times's last_synced > 24h " +
      "where the projects with no rows in the table should go first" in new TestCase {

        val project1Id   = projectIds.generateOne
        val project1Slug = projectSlugs.generateOne
        upsertProject(project1Id, project1Slug, eventDates.generateOne)

        val project2Id   = projectIds.generateOne
        val project2Slug = projectSlugs.generateOne
        upsertProject(project2Id, project2Slug, eventDates.generateOne)
        val project2lastSyncDate = relativeTimestamps(moreThanAgo = Duration.ofDays(7)).generateAs(LastSyncedDate)
        upsertCategorySyncTime(project2Id, categoryName, project2lastSyncDate)

        val project3Id   = projectIds.generateOne
        val project3Slug = projectSlugs.generateOne
        upsertProject(project3Id, project3Slug, eventDates.generateOne)
        val project3lastSyncDate =
          timestampsNotInTheFuture(butYoungerThan = project2lastSyncDate.value).generateAs(LastSyncedDate)
        upsertCategorySyncTime(project3Id, categoryName, project3lastSyncDate)

        finder.popEvent().unsafeRunSync() shouldBe Some(ProjectSyncEvent(project1Id, project1Slug))
        finder.popEvent().unsafeRunSync() shouldBe Some(ProjectSyncEvent(project2Id, project2Slug))
        finder.popEvent().unsafeRunSync() shouldBe Some(ProjectSyncEvent(project3Id, project3Slug))
        finder.popEvent().unsafeRunSync() shouldBe None
      }
  }

  private trait TestCase {
    val currentTime = Instant.now()
    val now         = mockFunction[Instant]
    now.expects().returning(currentTime).anyNumberOfTimes()
    private implicit val metricsRegistry:  TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    private implicit val queriesExecTimes: QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val finder = new EventFinderImpl[IO](now)
  }
}
