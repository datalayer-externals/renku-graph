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

package io.renku.eventlog.events.consumers.statuschange.rollbacktotriplesgenerated

import cats.effect.IO
import io.renku.eventlog.events.consumers.statuschange.DBUpdateResults
import io.renku.eventlog.api.events.StatusChangeEvent.RollbackToTriplesGenerated
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.eventlog.{InMemoryEventLogDbSpec, TypeSerializers}
import io.renku.events.consumers.Project
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.timestampsNotInTheFuture
import io.renku.graph.model.EventsGenerators.{eventBodies, eventIds}
import io.renku.graph.model.GraphModelGenerators.{projectIds, projectSlugs}
import io.renku.graph.model.events.EventStatus._
import io.renku.graph.model.events._
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class DbUpdaterSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with TypeSerializers
    with should.Matchers
    with MockFactory {

  "updateDB" should {

    s"change the status of the given event from $TransformingTriples to $TriplesGenerated" in new TestCase {

      val eventId      = addEvent(TransformingTriples)
      val otherEventId = addEvent(TransformingTriples)

      sessionResource
        .useK(dbUpdater updateDB RollbackToTriplesGenerated(eventId, project))
        .unsafeRunSync() shouldBe DBUpdateResults.ForProjects(
        projectSlug,
        Map(TransformingTriples -> -1, TriplesGenerated -> 1)
      )

      findEvent(CompoundEventId(eventId, projectId)).map(_._2)      shouldBe Some(TriplesGenerated)
      findEvent(CompoundEventId(otherEventId, projectId)).map(_._2) shouldBe Some(TransformingTriples)
    }

    s"do nothing if event is not in the $TransformingTriples status" in new TestCase {

      val invalidStatus = Gen.oneOf(EventStatus.all.filterNot(_ == TransformingTriples)).generateOne
      val eventId       = addEvent(invalidStatus)

      sessionResource
        .useK(dbUpdater updateDB RollbackToTriplesGenerated(eventId, project))
        .unsafeRunSync() shouldBe DBUpdateResults.ForProjects.empty

      findEvent(CompoundEventId(eventId, projectId)).map(_._2) shouldBe Some(invalidStatus)
    }
  }

  private trait TestCase {

    val projectId   = projectIds.generateOne
    val projectSlug = projectSlugs.generateOne
    val project     = Project(projectId, projectSlug)

    val currentTime = mockFunction[Instant]
    private implicit val metricsRegistry:  TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    private implicit val queriesExecTimes: QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val dbUpdater = new DbUpdater[IO](currentTime)

    val now = Instant.now()
    currentTime.expects().returning(now).anyNumberOfTimes()

    def addEvent(status: EventStatus): EventId = {
      val eventId = CompoundEventId(eventIds.generateOne, projectId)

      storeEvent(
        eventId,
        status,
        timestampsNotInTheFuture.generateAs(ExecutionDate),
        timestampsNotInTheFuture.generateAs(EventDate),
        eventBodies.generateOne,
        projectSlug = projectSlug
      )
      eventId.id
    }
  }
}
