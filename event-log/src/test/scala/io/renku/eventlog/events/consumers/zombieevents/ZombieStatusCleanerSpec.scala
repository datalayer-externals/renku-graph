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

package io.renku.eventlog.events.consumers.zombieevents

import cats.effect.IO
import cats.syntax.all._
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.eventlog.{InMemoryEventLogDbSpec, TypeSerializers}
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.EventContentGenerators._
import io.renku.graph.model.EventsGenerators._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.events.EventStatus.{AwaitingDeletion, Deleting, GeneratingTriples, New, TransformingTriples, TriplesGenerated}
import io.renku.graph.model.events._
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.time.temporal.ChronoUnit.MICROS

class ZombieStatusCleanerSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with TableDrivenPropertyChecks
    with MockFactory
    with TypeSerializers
    with should.Matchers {

  "cleanZombieStatus" should {

    forAll {
      Table(
        "current status"    -> "after update",
        GeneratingTriples   -> New,
        TransformingTriples -> TriplesGenerated,
        Deleting            -> AwaitingDeletion
      )
    } { (currentStatus, afterUpdateStatus) =>
      s"update event status to $afterUpdateStatus " +
        s"if event has status $currentStatus and so the event in the DB" in new TestCase {

          addZombieEvent(currentStatus)

          findEvent(eventId) shouldBe (executionDate, currentStatus, Some(zombieMessage)).some

          updater.cleanZombieStatus(ZombieEvent(eventId, projectSlug, currentStatus)).unsafeRunSync() shouldBe Updated

          findEvent(eventId) shouldBe (ExecutionDate(now), afterUpdateStatus, None).some
        }

      s"update event status to $afterUpdateStatus and remove the existing event delivery info " +
        s"if event has status $currentStatus and so the event in the DB" in new TestCase {

          addZombieEvent(currentStatus)
          upsertEventDeliveryInfo(eventId)

          findEvent(eventId)               shouldBe (executionDate, currentStatus, Some(zombieMessage)).some
          findAllEventDeliveries.map(_._1) shouldBe List(eventId)

          updater.cleanZombieStatus(ZombieEvent(eventId, projectSlug, currentStatus)).unsafeRunSync() shouldBe Updated

          findEvent(eventId)               shouldBe (ExecutionDate(now), afterUpdateStatus, None).some
          findAllEventDeliveries.map(_._1) shouldBe Nil
        }
    }

    "do nothing if the event does not exists" in new TestCase {

      val otherEventId = compoundEventIds.generateOne

      addZombieEvent(GeneratingTriples)

      findEvent(eventId) shouldBe (executionDate, GeneratingTriples, Some(zombieMessage)).some

      updater
        .cleanZombieStatus(ZombieEvent(otherEventId, projectSlug, GeneratingTriples))
        .unsafeRunSync() shouldBe NotUpdated

      findEvent(eventId) shouldBe (executionDate, GeneratingTriples, Some(zombieMessage)).some
    }
  }

  private trait TestCase {
    val currentTime = mockFunction[Instant]
    private implicit val metricsRegistry:  TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    private implicit val queriesExecTimes: QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val updater = new ZombieStatusCleanerImpl[IO](currentTime)

    val eventId       = compoundEventIds.generateOne
    val projectSlug   = projectSlugs.generateOne
    val executionDate = executionDates.generateOne
    val zombieMessage = EventMessage("Zombie Event")

    val now = Instant.now().truncatedTo(MICROS)
    currentTime.expects().returning(now)

    def addZombieEvent(status: EventStatus): Unit = storeEvent(eventId,
                                                               status,
                                                               executionDate,
                                                               eventDates.generateOne,
                                                               eventBodies.generateOne,
                                                               projectSlug = projectSlug,
                                                               maybeMessage = Some(zombieMessage)
    )
  }
}
