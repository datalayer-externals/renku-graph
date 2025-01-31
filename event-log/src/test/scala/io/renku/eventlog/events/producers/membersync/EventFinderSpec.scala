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
package membersync

import cats.effect.IO
import io.renku.eventlog.InMemoryEventLogDbSpec
import io.renku.eventlog.events.producers.SubscriptionDataProvisioning
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.EventContentGenerators._
import io.renku.graph.model.EventsGenerators._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.events.{EventDate, LastSyncedDate}
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.time.Duration

class EventFinderSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with SubscriptionDataProvisioning
    with MockFactory
    with should.Matchers {

  "popEvent" should {

    "return the event for the project with the latest event date " +
      s"when the subscription_category_sync_times table has no rows for the $categoryName" in new TestCase {

        finder.popEvent().unsafeRunSync() shouldBe None

        val projectSlug0 = projectSlugs.generateOne
        val eventDate0   = eventDates.generateOne
        upsertProject(compoundEventIds.generateOne, projectSlug0, eventDate0)

        val eventId1     = compoundEventIds.generateOne
        val projectSlug1 = projectSlugs.generateOne
        val eventDate1   = eventDates.generateOne
        upsertProject(eventId1, projectSlug1, eventDate1)
        upsertCategorySyncTime(eventId1.projectId,
                               commitsync.categoryName,
                               relativeTimestamps(moreThanAgo = Duration.ofDays(30)).generateAs(LastSyncedDate)
        )

        val projectSlugsByDateDecreasing = List(
          (projectSlug0, eventDate0),
          (projectSlug1, eventDate1)
        ).sortBy(_._2).map(_._1).reverse
        finder.popEvent().unsafeRunSync() shouldBe Some(MemberSyncEvent(projectSlugsByDateDecreasing.head))
        finder.popEvent().unsafeRunSync() shouldBe Some(MemberSyncEvent(projectSlugsByDateDecreasing.tail.head))
        finder.popEvent().unsafeRunSync() shouldBe None
      }

    "return projects with a latest event date less than an hour ago " +
      "and a last sync time more than 5 minutes ago " +
      "AND not projects with a latest event date less than an hour ago " +
      "and a last sync time less than 5 minutes ago" in new TestCase {
        val compoundId0  = compoundEventIds.generateOne
        val projectSlug0 = projectSlugs.generateOne
        val eventDate0   = EventDate(relativeTimestamps(lessThanAgo = Duration.ofMinutes(59)).generateOne)
        val lastSynced0 =
          LastSyncedDate(relativeTimestamps(moreThanAgo = Duration.ofMinutes(5).plusSeconds(1)).generateOne)
        upsertProject(compoundId0, projectSlug0, eventDate0)
        upsertCategorySyncTime(compoundId0.projectId, categoryName, lastSynced0)

        val compoundId1  = compoundEventIds.generateOne
        val projectSlug1 = projectSlugs.generateOne
        val eventDate1   = EventDate(relativeTimestamps(lessThanAgo = Duration.ofMinutes(59)).generateOne)
        val lastSynced1 =
          LastSyncedDate(relativeTimestamps(lessThanAgo = Duration.ofMinutes(5).minusSeconds(1)).generateOne)
        upsertProject(compoundId1, projectSlug1, eventDate1)
        upsertCategorySyncTime(compoundId1.projectId, categoryName, lastSynced1)

        finder.popEvent().unsafeRunSync() shouldBe Some(MemberSyncEvent(projectSlug0))
        finder.popEvent().unsafeRunSync() shouldBe None
      }

    "return projects with a latest event date less than a day ago " +
      "and a last sync time more than a hour ago " +
      "but not projects with a latest event date less than a day ago " +
      "and a last sync time less than an hour ago" in new TestCase {
        val compoundId0  = compoundEventIds.generateOne
        val projectSlug0 = projectSlugs.generateOne
        val eventDate0 = EventDate(
          relativeTimestamps(lessThanAgo = Duration.ofHours(23), moreThanAgo = Duration.ofMinutes(65)).generateOne
        )
        val lastSynced0 = LastSyncedDate(relativeTimestamps(moreThanAgo = Duration.ofMinutes(65)).generateOne)
        upsertProject(compoundId0, projectSlug0, eventDate0)
        upsertCategorySyncTime(compoundId0.projectId, categoryName, lastSynced0)

        val compoundId1  = compoundEventIds.generateOne
        val projectSlug1 = projectSlugs.generateOne
        val eventDate1 = EventDate(
          relativeTimestamps(lessThanAgo = Duration.ofHours(23), moreThanAgo = Duration.ofMinutes(65)).generateOne
        )
        val lastSynced1 = LastSyncedDate(relativeTimestamps(lessThanAgo = Duration.ofMinutes(55)).generateOne)
        upsertProject(compoundId1, projectSlug1, eventDate1)
        upsertCategorySyncTime(compoundId1.projectId, categoryName, lastSynced1)

        finder.popEvent().unsafeRunSync() shouldBe Some(MemberSyncEvent(projectSlug0))
        finder.popEvent().unsafeRunSync() shouldBe None
      }

    "return projects with a latest event date more than a day ago " +
      "and a last sync time more than a day ago " +
      "but not projects with a latest event date more than a day ago " +
      "and a last sync time less than a day ago" in new TestCase {
        val compoundId0  = compoundEventIds.generateOne
        val projectSlug0 = projectSlugs.generateOne
        val eventDate0   = EventDate(relativeTimestamps(moreThanAgo = Duration.ofHours(25)).generateOne)
        val lastSynced0  = LastSyncedDate(relativeTimestamps(moreThanAgo = Duration.ofHours(25)).generateOne)
        upsertProject(compoundId0, projectSlug0, eventDate0)
        upsertCategorySyncTime(compoundId0.projectId, categoryName, lastSynced0)

        val compoundId1  = compoundEventIds.generateOne
        val projectSlug1 = projectSlugs.generateOne
        val eventDate1   = EventDate(relativeTimestamps(moreThanAgo = Duration.ofHours(25)).generateOne)
        val lastSynced1  = LastSyncedDate(relativeTimestamps(lessThanAgo = Duration.ofHours(23)).generateOne)
        upsertProject(compoundId1, projectSlug1, eventDate1)
        upsertCategorySyncTime(compoundId1.projectId, categoryName, lastSynced1)

        finder.popEvent().unsafeRunSync() shouldBe Some(MemberSyncEvent(projectSlug0))
        finder.popEvent().unsafeRunSync() shouldBe None
      }
  }

  private trait TestCase {
    private implicit val metricsRegistry:  TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    private implicit val queriesExecTimes: QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val finder = new EventFinderImpl[IO]
  }
}
