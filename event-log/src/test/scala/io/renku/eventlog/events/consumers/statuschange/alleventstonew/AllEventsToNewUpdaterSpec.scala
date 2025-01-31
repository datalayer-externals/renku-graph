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

package io.renku.eventlog.events.consumers.statuschange
package alleventstonew

import cats.effect.IO
import cats.syntax.all._
import io.circe.literal._
import io.renku.eventlog.api.events.StatusChangeEvent.AllEventsToNew
import io.renku.eventlog.events.consumers.statuschange.{DBUpdateResults, categoryName}
import io.renku.eventlog.metrics.QueriesExecutionTimes
import io.renku.eventlog.{InMemoryEventLogDbSpec, TypeSerializers}
import io.renku.events.consumers.Project
import io.renku.events.producers.EventSender
import io.renku.events.{CategoryName, EventRequestContent}
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.{timestamps, timestampsNotInTheFuture}
import io.renku.graph.model.EventContentGenerators.{eventDates, eventMessages}
import io.renku.graph.model.EventsGenerators._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.events.{EventDate, EventId, EventStatus, ExecutionDate}
import io.renku.metrics.TestMetricsRegistry
import io.renku.testtools.IOSpec
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Random

class AllEventsToNewUpdaterSpec
    extends AnyWordSpec
    with IOSpec
    with InMemoryEventLogDbSpec
    with TypeSerializers
    with should.Matchers
    with MockFactory {

  "updateDB" should {

    "send ProjectEventsToNew events for all the projects" in new TestCase {

      val projects = projectObjects.generateNonEmptyList().toList

      projects foreach { project =>
        if (Random.nextBoolean()) addEvent(project)
        else upsertProject(project.id, project.slug, eventDates.generateOne)
      }

      projects foreach { project =>
        (eventSender
          .sendEvent(_: EventRequestContent.NoPayload, _: EventSender.EventContext))
          .expects(
            EventRequestContent.NoPayload(toEventJson(project)),
            EventSender.EventContext(CategoryName(projecteventstonew.eventType.show),
                                     show"$categoryName: generating ${projecteventstonew.eventType} for $project failed"
            )
          )
          .returning(().pure[IO])
      }

      sessionResource.useK(dbUpdater updateDB AllEventsToNew).unsafeRunSync() shouldBe DBUpdateResults.ForProjects.empty
    }

    "do not send any events if there are no projects in the DB" in new TestCase {
      sessionResource.useK(dbUpdater updateDB AllEventsToNew).unsafeRunSync() shouldBe DBUpdateResults.ForProjects.empty
    }
  }

  private trait TestCase {

    val eventSender = mock[EventSender[IO]]
    private implicit val metricsRegistry:  TestMetricsRegistry[IO]   = TestMetricsRegistry[IO]
    private implicit val queriesExecTimes: QueriesExecutionTimes[IO] = QueriesExecutionTimes[IO]().unsafeRunSync()
    val dbUpdater = new DbUpdater[IO](eventSender)

    def addEvent(project: Project): EventId = {
      val eventId = compoundEventIds.generateOne.copy(projectId = project.id)
      val status  = eventStatuses.generateOne
      storeEvent(
        eventId,
        status,
        timestamps.generateAs(ExecutionDate),
        timestampsNotInTheFuture.generateAs(EventDate),
        eventBodies.generateOne,
        maybeMessage = status match {
          case _: EventStatus.FailureStatus => eventMessages.generateSome
          case _ => eventMessages.generateOption
        },
        maybeEventPayload = status match {
          case EventStatus.TriplesStore | EventStatus.TriplesGenerated => zippedEventPayloads.generateSome
          case EventStatus.AwaitingDeletion                            => zippedEventPayloads.generateOption
          case _                                                       => zippedEventPayloads.generateNone
        },
        projectSlug = project.slug
      )

      status match {
        case EventStatus.TriplesGenerated | EventStatus.TriplesStore =>
          upsertProcessingTime(eventId, status, eventProcessingTimes.generateOne)
        case EventStatus.AwaitingDeletion =>
          if (Random.nextBoolean()) {
            upsertProcessingTime(eventId, status, eventProcessingTimes.generateOne)
          } else ()
        case _ => ()
      }

      eventId.id
    }
  }

  private lazy val projectObjects: Gen[Project] = (projectIds -> projectSlugs).mapN(Project.apply)

  private def toEventJson(project: Project) = json"""{
    "categoryName": "EVENTS_STATUS_CHANGE",
    "project": {
      "id":   ${project.id},
      "slug": ${project.slug}
    },
    "subCategory": "ProjectEventsToNew"
  }"""
}
