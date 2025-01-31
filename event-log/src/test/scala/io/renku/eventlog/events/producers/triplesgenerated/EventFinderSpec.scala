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
package triplesgenerated

import cats.effect.IO
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.eventlog.InMemoryEventLogDbSpec
import io.renku.eventlog.events.producers.ProjectPrioritisation.Priority.MaxPriority
import io.renku.eventlog.events.producers.ProjectPrioritisation.{Priority, ProjectInfo}
import io.renku.eventlog.metrics.TestEventStatusGauges._
import io.renku.eventlog.metrics.{EventStatusGauges, QueriesExecutionTimes, TestEventStatusGauges}
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.EventContentGenerators._
import io.renku.graph.model.EventsGenerators._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.events.EventStatus._
import io.renku.graph.model.events._
import io.renku.graph.model.projects.{GitLabId, Slug}
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

private class EventFinderSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with MockFactory
    with should.Matchers
    with OptionValues {

  "popEvent" should {

    s"return the most recent event in status $TriplesGenerated or $TransformationRecoverableFailure " +
      s"and mark it as $TransformingTriples" in new TestCase {

        val projectId   = projectIds.generateOne
        val projectSlug = projectSlugs.generateOne

        val (event1Id, _, latestEventDate, _, eventPayload1) = createEvent(
          status = TriplesGenerated,
          eventDate = timestampsNotInTheFuture.generateAs(EventDate),
          projectId = projectId,
          projectSlug = projectSlug
        )

        createEvent(
          status = TransformationRecoverableFailure,
          timestamps(max = latestEventDate.value).generateAs(EventDate),
          projectId = projectId,
          projectSlug = projectSlug
        )

        findEvents(TransformingTriples) shouldBe List.empty

        givenPrioritisation(
          takes = List(ProjectInfo(projectId, projectSlug, latestEventDate, 0)),
          totalOccupancy = 0,
          returns = List(ProjectIds(projectId, projectSlug) -> MaxPriority)
        )

        val TriplesGeneratedEvent(actualEventId, actualSlug, actualPayload) = finder.popEvent().unsafeRunSync().value
        actualEventId     shouldBe event1Id
        actualSlug        shouldBe projectSlug
        actualPayload.value should contain theSameElementsAs eventPayload1.value

        findEvents(TransformingTriples).noBatchDate shouldBe List((event1Id, executionDate))

        givenPrioritisation(takes = Nil, totalOccupancy = 1, returns = Nil)

        finder.popEvent().unsafeRunSync() shouldBe None

        gauges.awaitingTransformation.getValue(projectSlug).unsafeRunSync() shouldBe -1d
        gauges.underTransformation.getValue(projectSlug).unsafeRunSync()    shouldBe 1d

        findEvents(TransformingTriples).noBatchDate shouldBe List((event1Id, executionDate))
      }

    "return an event if there are multiple latest events with the same date" in new TestCase {

      val projectId       = projectIds.generateOne
      val projectSlug     = projectSlugs.generateOne
      val latestEventDate = eventDates.generateOne

      val (event1Id, _, _, _, event1Payload) = createEvent(
        status = Gen.oneOf(TriplesGenerated, TransformationRecoverableFailure).generateOne,
        eventDate = latestEventDate,
        projectId = projectId,
        projectSlug = projectSlug
      )

      val (event2Id, _, _, _, event2Payload) = createEvent(
        status = Gen.oneOf(TriplesGenerated, TransformationRecoverableFailure).generateOne,
        eventDate = latestEventDate,
        projectId = projectId,
        projectSlug = projectSlug
      )

      findEvents(TransformingTriples) shouldBe List.empty

      // 1st event with the same event date
      givenPrioritisation(
        takes = List(ProjectInfo(projectId, projectSlug, latestEventDate, 0)),
        totalOccupancy = 0,
        returns = List(ProjectIds(projectId, projectSlug) -> MaxPriority)
      )

      val TriplesGeneratedEvent(actualEventId, actualSlug, actualPayload) = finder.popEvent().unsafeRunSync().value
      if (actualEventId == event1Id) {
        actualEventId     shouldBe event1Id
        actualSlug        shouldBe projectSlug
        actualPayload.value should contain theSameElementsAs event1Payload.value
      } else {
        actualEventId     shouldBe event2Id
        actualSlug        shouldBe projectSlug
        actualPayload.value should contain theSameElementsAs event2Payload.value
      }

      findEvents(TransformingTriples).noBatchDate should {
        be(List((event1Id, executionDate))) or be(List((event2Id, executionDate)))
      }

      gauges.awaitingTransformation.getValue(projectSlug).unsafeRunSync() shouldBe -1d
      gauges.underTransformation.getValue(projectSlug).unsafeRunSync()    shouldBe 1d

      // 2nd event with the same event date
      givenPrioritisation(
        takes = List(ProjectInfo(projectId, projectSlug, latestEventDate, 1)),
        totalOccupancy = 1,
        returns = List(ProjectIds(projectId, projectSlug) -> MaxPriority)
      )

      val TriplesGeneratedEvent(nextActualEventId, nextActualSlug, nextActualPayload) =
        finder.popEvent().unsafeRunSync().value
      if (nextActualEventId == event1Id) {
        nextActualEventId     shouldBe event1Id
        nextActualSlug        shouldBe projectSlug
        nextActualPayload.value should contain theSameElementsAs event1Payload.value
      } else {
        nextActualEventId     shouldBe event2Id
        nextActualSlug        shouldBe projectSlug
        nextActualPayload.value should contain theSameElementsAs event2Payload.value
      }

      findEvents(TransformingTriples).noBatchDate should contain theSameElementsAs List(event1Id -> executionDate,
                                                                                        event2Id -> executionDate
      )

      gauges.awaitingTransformation.getValue(projectSlug).unsafeRunSync() shouldBe -2d
      gauges.underTransformation.getValue(projectSlug).unsafeRunSync()    shouldBe 2d

      // no more events left
      givenPrioritisation(takes = Nil, totalOccupancy = 2, returns = Nil)

      finder.popEvent().unsafeRunSync() shouldBe None
    }

    "return an event with the latest event date " +
      s"and status $TriplesGenerated or $TransformationRecoverableFailure " +
      s"and mark it as $TransformingTriples " +
      s"case - when a newer event arrive after the pop" in new TestCase {

        val projectId   = projectIds.generateOne
        val projectSlug = projectSlugs.generateOne

        val (event1Id, _, latestEventDate, _, eventPayload1) = createEvent(
          status = TriplesGenerated,
          eventDate = timestampsNotInTheFuture.generateAs(EventDate),
          projectId = projectId,
          projectSlug = projectSlug
        )

        findEvents(TransformingTriples) shouldBe List.empty

        givenPrioritisation(
          takes = List(ProjectInfo(projectId, projectSlug, latestEventDate, 0)),
          totalOccupancy = 0,
          returns = List(ProjectIds(projectId, projectSlug) -> MaxPriority)
        )

        val TriplesGeneratedEvent(actualEvent1Id, actualEvent1Slug, actualEvent1Payload) =
          finder.popEvent().unsafeRunSync().value

        actualEvent1Id          shouldBe event1Id
        actualEvent1Slug        shouldBe projectSlug
        actualEvent1Payload.value should contain theSameElementsAs eventPayload1.value

        gauges.awaitingTransformation.getValue(projectSlug).unsafeRunSync() shouldBe -1d
        gauges.underTransformation.getValue(projectSlug).unsafeRunSync()    shouldBe 1d

        findEvents(TransformingTriples).noBatchDate shouldBe List((event1Id, executionDate))

        val (event2Id, _, newerLatestEventDate, _, eventPayload2) = createEvent(
          status = TransformationRecoverableFailure,
          timestamps(min = latestEventDate.value, max = now).generateAs(EventDate),
          projectId = projectId,
          projectSlug = projectSlug
        )

        givenPrioritisation(
          takes = List(ProjectInfo(projectId, projectSlug, newerLatestEventDate, 1)),
          totalOccupancy = 1,
          returns = List(ProjectIds(projectId, projectSlug) -> MaxPriority)
        )

        val TriplesGeneratedEvent(actualEvent2Id, actualEvent2Slug, actualEvent2Payload) =
          finder.popEvent().unsafeRunSync().value

        actualEvent2Id          shouldBe event2Id
        actualEvent2Slug        shouldBe projectSlug
        actualEvent2Payload.value should contain theSameElementsAs eventPayload2.value

        gauges.awaitingTransformation.getValue(projectSlug).unsafeRunSync() shouldBe -2d
        gauges.underTransformation.getValue(projectSlug).unsafeRunSync()    shouldBe 2d

        findEvents(TransformingTriples).noBatchDate shouldBe List((event1Id, executionDate), (event2Id, executionDate))
      }

    s"skip events in $TriplesGenerated status which do not have payload - within a project" in new TestCase {

      val projectId   = projectIds.generateOne
      val projectSlug = projectSlugs.generateOne

      val event1Id   = compoundEventIds.generateOne.copy(projectId = projectId)
      val event1Date = timestampsNotInTheFuture.generateAs(EventDate)
      storeEvent(
        event1Id,
        TriplesGenerated,
        executionDatesInThePast.generateOne,
        event1Date,
        eventBodies.generateOne,
        projectSlug = projectSlug,
        maybeEventPayload = None
      )

      val (event2Id, _, _, _, eventPayload2) = createEvent(
        status = TriplesGenerated,
        eventDate = timestamps(max = event1Date.value).generateAs(EventDate),
        projectId = projectId,
        projectSlug = projectSlug
      )

      givenPrioritisation(
        takes = List(ProjectInfo(projectId, projectSlug, event1Date, 0)),
        totalOccupancy = 0,
        returns = List(ProjectIds(projectId, projectSlug) -> MaxPriority)
      )

      val TriplesGeneratedEvent(actualEventId, actualEventSlug, actualEventPayload) =
        finder.popEvent().unsafeRunSync().value

      actualEventId          shouldBe event2Id
      actualEventSlug        shouldBe projectSlug
      actualEventPayload.value should contain theSameElementsAs eventPayload2.value

      gauges.awaitingTransformation.getValue(projectSlug).unsafeRunSync() shouldBe -1d
      gauges.underTransformation.getValue(projectSlug).unsafeRunSync()    shouldBe 1d

      findEvents(TransformingTriples).noBatchDate shouldBe List((event2Id, executionDate))
    }

    s"skip projects with events in $TriplesGenerated status which do not have payload" in new TestCase {

      val event1ProjectId   = projectIds.generateOne
      val event1ProjectSlug = projectSlugs.generateOne

      val event1Id   = compoundEventIds.generateOne.copy(projectId = event1ProjectId)
      val event1Date = timestampsNotInTheFuture.generateAs(EventDate)
      storeEvent(
        event1Id,
        TriplesGenerated,
        executionDatesInThePast.generateOne,
        event1Date,
        eventBodies.generateOne,
        projectSlug = event1ProjectSlug,
        maybeEventPayload = None
      )

      val event2ProjectId   = projectIds.generateOne
      val event2ProjectSlug = projectSlugs.generateOne
      val event2Date        = timestamps(max = event1Date.value).generateAs(EventDate)
      val (event2Id, _, _, _, eventPayload2) = createEvent(
        status = TriplesGenerated,
        eventDate = event2Date,
        projectId = event2ProjectId,
        projectSlug = event2ProjectSlug
      )

      givenPrioritisation(
        takes = List(ProjectInfo(event2ProjectId, event2ProjectSlug, event2Date, 0)),
        totalOccupancy = 0,
        returns = List(ProjectIds(event2ProjectId, event2ProjectSlug) -> MaxPriority)
      )

      val TriplesGeneratedEvent(actualEventId, actualEventSlug, actualEventPayload) =
        finder.popEvent().unsafeRunSync().value
      actualEventId          shouldBe event2Id
      actualEventSlug        shouldBe event2ProjectSlug
      actualEventPayload.value should contain theSameElementsAs eventPayload2.value

      findEvents(TransformingTriples).noBatchDate shouldBe List((event2Id, executionDate))

      gauges.awaitingTransformation.getValue(event2ProjectSlug).unsafeRunSync() shouldBe -1d
      gauges.underTransformation.getValue(event2ProjectSlug).unsafeRunSync()    shouldBe 1d
    }

    "return no event when execution date is in the future " +
      s"and status $TriplesGenerated or $TransformationRecoverableFailure " in new TestCase {

        val projectId   = projectIds.generateOne
        val projectSlug = projectSlugs.generateOne

        val (_, _, event1Date, _, _) = createEvent(
          status = TriplesGenerated,
          eventDate = timestamps(max = Instant.now().minusSeconds(5)).generateAs(EventDate),
          projectId = projectId,
          projectSlug = projectSlug
        )

        val (_, _, event2Date, _, _) = createEvent(
          status = TransformationRecoverableFailure,
          eventDate = EventDate(event1Date.value plusSeconds 1),
          executionDate = ExecutionDate(timestampsInTheFuture.generateOne),
          projectId = projectId,
          projectSlug = projectSlug
        )

        createEvent(
          status = TriplesGenerated,
          eventDate = EventDate(event2Date.value plusSeconds 1),
          executionDate = ExecutionDate(timestampsInTheFuture.generateOne),
          projectId = projectId,
          projectSlug = projectSlug
        )

        findEvents(TransformingTriples) shouldBe List.empty

        givenPrioritisation(takes = Nil, totalOccupancy = 0, returns = Nil)

        finder.popEvent().unsafeRunSync() shouldBe None
      }

    "return events from all the projects" in new TestCase {

      val events = readyStatuses
        .generateNonEmptyList(min = 2)
        .map(createEvent(_))
        .toList

      findEvents(TransformingTriples) shouldBe List.empty

      events.sortBy(_._3).reverse.zipWithIndex foreach { case ((eventId, _, eventDate, projectSlug, _), idx) =>
        givenPrioritisation(
          takes = List(ProjectInfo(eventId.projectId, projectSlug, eventDate, 0)),
          totalOccupancy = idx,
          returns = List(ProjectIds(eventId.projectId, projectSlug) -> MaxPriority)
        )
      }

      events foreach { _ =>
        finder.popEvent().unsafeRunSync() shouldBe a[Some[_]]
      }

      events foreach { case (_, _, _, slug, _) =>
        gauges.awaitingTransformation.getValue(slug).unsafeRunSync() shouldBe -1d
        gauges.underTransformation.getValue(slug).unsafeRunSync()    shouldBe 1d
      }

      findEvents(status = TransformingTriples).eventIdsOnly should contain theSameElementsAs events.map(_._1)
    }

    "return events from all the projects - case with projectsFetchingLimit > 1" in new TestCaseCommons {

      val eventLogFind = new EventFinderImpl[IO](currentTime, projectsFetchingLimit = 5, projectPrioritisation)

      val events = readyStatuses
        .generateNonEmptyList(min = 3, max = 6)
        .toList
        .flatMap { status =>
          val projectId   = projectIds.generateOne
          val projectSlug = projectSlugs.generateOne
          (1 to positiveInts(max = 2).generateOne.value)
            .map(_ => createEvent(status, projectId = projectId, projectSlug = projectSlug))
        }

      findEvents(TransformingTriples) shouldBe List.empty

      val eventsGroupedByProjects = events.groupBy(_._1.projectId)

      eventsGroupedByProjects.zipWithIndex foreach {
        case ((projectId, (_, _, _, projectSlug, _) :: _), idx) =>
          (projectPrioritisation.prioritise _)
            .expects(*, idx)
            .returning(List(ProjectIds(projectId, projectSlug) -> MaxPriority))
        case ((_, Nil), _) => ()
      }

      eventsGroupedByProjects foreach { _ =>
        eventLogFind.popEvent().unsafeRunSync() shouldBe a[Some[_]]
      }

      eventsGroupedByProjects foreach { case (_, list) =>
        val slug = list.head._4
        gauges.awaitingTransformation.getValue(slug).unsafeRunSync() shouldBe -1d
        gauges.underTransformation.getValue(slug).unsafeRunSync()    shouldBe 1d
      }

      findEvents(TransformingTriples).eventIdsOnly should contain theSameElementsAs eventsGroupedByProjects.map {
        case (_, projectEvents) => projectEvents.maxBy(_._3)._1
      }.toList

      givenPrioritisation(takes = Nil, totalOccupancy = eventsGroupedByProjects.size, returns = Nil)

      eventLogFind.popEvent().unsafeRunSync() shouldBe None
    }
  }

  private trait TestCaseCommons {
    val now           = Instant.now()
    val executionDate = ExecutionDate(now)
    val currentTime   = mockFunction[Instant]
    currentTime.expects().returning(now).anyNumberOfTimes()

    implicit val gauges:                  EventStatusGauges[IO]     = TestEventStatusGauges[IO]
    private implicit val metricsRegistry: TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    implicit val queriesExecTimes:        QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val projectPrioritisation = mock[ProjectPrioritisation[IO]]

    def givenPrioritisation(takes: List[ProjectInfo], totalOccupancy: Long, returns: List[(ProjectIds, Priority)]) =
      (projectPrioritisation.prioritise _)
        .expects(takes, totalOccupancy)
        .returning(returns)
  }

  private trait TestCase extends TestCaseCommons {
    val finder = new EventFinderImpl[IO](currentTime, projectsFetchingLimit = 1, projectPrioritisation)
  }

  private def executionDatesInThePast: Gen[ExecutionDate] = timestampsNotInTheFuture map ExecutionDate.apply

  private def readyStatuses = Gen.oneOf(TriplesGenerated, TransformationRecoverableFailure)

  private def createEvent(status:        EventStatus,
                          eventDate:     EventDate = eventDates.generateOne,
                          executionDate: ExecutionDate = executionDatesInThePast.generateOne,
                          batchDate:     BatchDate = batchDates.generateOne,
                          projectId:     GitLabId = projectIds.generateOne,
                          projectSlug:   Slug = projectSlugs.generateOne,
                          eventPayload:  ZippedEventPayload = zippedEventPayloads.generateOne
  ): (CompoundEventId, EventBody, EventDate, Slug, ZippedEventPayload) = {
    val eventId   = compoundEventIds.generateOne.copy(projectId = projectId)
    val eventBody = eventBodies.generateOne

    storeEvent(
      eventId,
      status,
      executionDate,
      eventDate,
      eventBody,
      batchDate = batchDate,
      projectSlug = projectSlug,
      maybeEventPayload = eventPayload.some
    )

    (eventId, eventBody, eventDate, projectSlug, eventPayload)
  }
}
