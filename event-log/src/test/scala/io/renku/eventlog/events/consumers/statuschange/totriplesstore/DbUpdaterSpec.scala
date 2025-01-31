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

package io.renku.eventlog.events.consumers.statuschange.totriplesstore

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all._
import io.renku.eventlog.api.events.StatusChangeEvent.ToTriplesStore
import io.renku.eventlog.events.consumers.statuschange.SkunkExceptionsGenerators.postgresErrors
import io.renku.eventlog.events.consumers.statuschange.{DBUpdateResults, DeliveryInfoRemover}
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.eventlog.{InMemoryEventLogDbSpec, TypeSerializers}
import io.renku.events.consumers.ConsumersModelGenerators
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.{exceptions, timestamps}
import io.renku.graph.model.EventContentGenerators.eventDates
import io.renku.graph.model.EventsGenerators
import io.renku.graph.model.EventsGenerators.eventProcessingTimes
import io.renku.graph.model.events.EventStatus._
import io.renku.graph.model.events._
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import skunk.SqlState

import java.time.Instant

class DbUpdaterSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with TypeSerializers
    with should.Matchers
    with MockFactory {

  "updateDB" should {

    "change the status of all events in statuses before TRIPLES_STORE up to the current event with the status TRIPLES_STORE" in new TestCase {

      val eventDate = eventDates.generateOne

      val eventsToUpdate = statusesToUpdate.map(addEvent(_, timestamps(max = eventDate.value).generateAs(EventDate)))
      val eventsToSkip = EventStatus.all
        .diff(statusesToUpdate)
        .map(addEvent(_, timestamps(max = eventDate.value).generateAs(EventDate))) +
        addEvent(TriplesGenerated, timestamps(min = eventDate.value, max = now).generateAs(EventDate))

      val event = addEvent(TransformingTriples, eventDate)

      val statusChangeEvent = ToTriplesStore(event._1, project, eventProcessingTimes.generateOne)

      givenDeliveryInfoRemoved(statusChangeEvent.eventId)

      sessionResource.useK(dbUpdater updateDB statusChangeEvent).unsafeRunSync() shouldBe DBUpdateResults
        .ForProjects(
          project.slug,
          statusesToUpdate
            .map(_ -> -1)
            .toMap +
            (TransformingTriples -> (-1 /* for the event */ - 1 /* for the old TransformingTriples */ )) +
            (TriplesStore        -> (eventsToUpdate.size + 1 /* for the event */ ))
        )

      findFullEvent(CompoundEventId(event._1, project.id))
        .map { case (_, status, _, maybePayload, processingTimes) =>
          status        shouldBe TriplesStore
          maybePayload  shouldBe a[Some[_]]
          processingTimes should contain(statusChangeEvent.processingTime)
        }
        .getOrElse(fail("No event found for main event"))

      eventsToUpdate.map { case (eventId, status, _, originalPayload, originalProcessingTimes) =>
        findFullEvent(CompoundEventId(eventId, project.id))
          .map { case (_, status, maybeMessage, maybePayload, processingTimes) =>
            status               shouldBe TriplesStore
            maybeMessage         shouldBe None
            (maybePayload.map(_.value) -> originalPayload.map(_.value)) mapN (_ should contain theSameElementsAs _)
            processingTimes      shouldBe originalProcessingTimes
          }
          .getOrElse(fail(s"No event found with old $status status"))
      }

      eventsToSkip.map { case (eventId, originalStatus, originalMessage, originalPayload, originalProcessingTimes) =>
        findFullEvent(CompoundEventId(eventId, project.id))
          .map { case (_, status, maybeMessage, maybePayload, processingTimes) =>
            status               shouldBe originalStatus
            maybeMessage         shouldBe originalMessage
            (maybePayload.map(_.value) -> originalPayload.map(_.value)) mapN (_ should contain theSameElementsAs _)
            processingTimes      shouldBe originalProcessingTimes
          }
          .getOrElse(fail(s"No event found with old $originalStatus status"))
      }
    }

    "change the status of all events older than the current event but not the events with the same date" in new TestCase {
      val eventDate = eventDates.generateOne
      val event1    = addEvent(TransformingTriples, eventDate)
      val event2    = addEvent(TriplesGenerated, eventDate)

      val statusChangeEvent =
        ToTriplesStore(event1._1, project, eventProcessingTimes.generateOne)

      givenDeliveryInfoRemoved(statusChangeEvent.eventId)

      sessionResource.useK(dbUpdater updateDB statusChangeEvent).unsafeRunSync() shouldBe DBUpdateResults
        .ForProjects(
          project.slug,
          statusCount = Map(TransformingTriples -> -1, TriplesStore -> 1)
        )

      findFullEvent(CompoundEventId(event1._1, project.id))
        .map { case (_, status, _, maybePayload, processingTimes) =>
          status        shouldBe TriplesStore
          maybePayload  shouldBe a[Some[_]]
          processingTimes should contain(statusChangeEvent.processingTime)
        }
        .getOrElse(fail("No event found for main event"))

      findFullEvent(CompoundEventId(event2._1, project.id)).map(_._2) shouldBe TriplesGenerated.some
    }

    (EventStatus.all - TransformingTriples) foreach { invalidStatus =>
      s"do nothing if event in $invalidStatus" in new TestCase {
        val latestEventDate = eventDates.generateOne
        val eventId         = addEvent(invalidStatus, latestEventDate)._1
        val ancestorEventId = addEvent(
          TransformingTriples,
          timestamps(max = latestEventDate.value.minusSeconds(1)).generateAs(EventDate)
        )._1

        val statusChangeEvent =
          ToTriplesStore(eventId, project, eventProcessingTimes.generateOne)

        givenDeliveryInfoRemoved(statusChangeEvent.eventId)

        sessionResource
          .useK(dbUpdater updateDB statusChangeEvent)
          .unsafeRunSync() shouldBe DBUpdateResults.ForProjects.empty

        findFullEvent(CompoundEventId(eventId, project.id)).map(_._2)         shouldBe invalidStatus.some
        findFullEvent(CompoundEventId(ancestorEventId, project.id)).map(_._2) shouldBe TransformingTriples.some
      }
    }
  }

  "onRollback" should {

    "retry the updateDB procedure on DeadlockDetected" in new TestCase {

      val eventDate = eventDates.generateOne

      // event to update =
      addEvent(New, timestamps(max = eventDate.value).generateAs(EventDate))

      // event to skip
      addEvent(EventStatus.all.diff(statusesToUpdate).head, timestamps(max = eventDate.value).generateAs(EventDate))

      val event = addEvent(TransformingTriples, eventDate)

      val statusChangeEvent = ToTriplesStore(event._1, project, eventProcessingTimes.generateOne)

      givenDeliveryInfoRemoved(statusChangeEvent.eventId)

      val deadlockException = postgresErrors(SqlState.DeadlockDetected).generateOne
      (dbUpdater onRollback statusChangeEvent)
        .apply(deadlockException)
        .unsafeRunSync() shouldBe DBUpdateResults
        .ForProjects(
          project.slug,
          Map(
            New                 -> -1 /* for the event to update */,
            TransformingTriples -> -1 /* for the event */,
            TriplesStore        -> 2 /* event to update + the event */
          )
        )

      findFullEvent(CompoundEventId(event._1, project.id))
        .map { case (_, status, _, maybePayload, processingTimes) =>
          status        shouldBe TriplesStore
          maybePayload  shouldBe a[Some[_]]
          processingTimes should contain(statusChangeEvent.processingTime)
        }
        .getOrElse(fail("No event found for main event"))
    }

    "clean the delivery info for the event when Exception different than DeadlockDetected " +
      "and rethrow the exception" in new TestCase {

        val event = ToTriplesStore(EventsGenerators.eventIds.generateOne, project, eventProcessingTimes.generateOne)

        givenDeliveryInfoRemoved(event.eventId)

        val exception = exceptions.generateOne
        intercept[Exception] {
          (dbUpdater onRollback event)
            .apply(exception)
            .unsafeRunSync() shouldBe DBUpdateResults.ForProjects.empty
        } shouldBe exception
      }
  }

  private trait TestCase {

    val statusesToUpdate = Set(New,
                               GeneratingTriples,
                               GenerationRecoverableFailure,
                               TriplesGenerated,
                               TransformingTriples,
                               TransformationRecoverableFailure
    )

    val project = ConsumersModelGenerators.consumerProjects.generateOne

    private val currentTime         = mockFunction[Instant]
    private val deliveryInfoRemover = mock[DeliveryInfoRemover[IO]]
    private implicit val metricsRegistry:  TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    private implicit val queriesExecTimes: QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val dbUpdater = new DbUpdater[IO](deliveryInfoRemover, currentTime)

    val now = Instant.now()
    currentTime.expects().returning(now).anyNumberOfTimes()

    def givenDeliveryInfoRemoved(eventId: CompoundEventId) =
      (deliveryInfoRemover.deleteDelivery _).expects(eventId).returning(Kleisli.pure(()))

    def addEvent(status:    EventStatus,
                 eventDate: EventDate
    ): (EventId, EventStatus, Option[EventMessage], Option[ZippedEventPayload], List[EventProcessingTime]) =
      storeGeneratedEvent(status, eventDate, project.id, project.slug)

    def findFullEvent(
        eventId: CompoundEventId
    ): Option[(EventId, EventStatus, Option[EventMessage], Option[ZippedEventPayload], List[EventProcessingTime])] = {
      val maybeEvent     = findEvent(eventId)
      val maybePayload   = findPayload(eventId).map(_._2)
      val processingTime = findProcessingTime(eventId)
      maybeEvent.map { case (_, status, maybeMessage) =>
        (eventId.id, status, maybeMessage, maybePayload, processingTime.map(_._2))
      }
    }
  }
}
