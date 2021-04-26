/*
 * Copyright 2021 Swiss Data Science Center (SDSC)
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

package io.renku.eventlog.statuschange.commands

import cats.effect.IO
import ch.datascience.db.SqlStatement
import ch.datascience.generators.Generators.Implicits._
import ch.datascience.graph.model.EventsGenerators.{batchDates, compoundEventIds, eventBodies, eventProcessingTimes}
import ch.datascience.graph.model.GraphModelGenerators.projectPaths
import ch.datascience.graph.model.events.EventStatus
import ch.datascience.graph.model.events.EventStatus._
import ch.datascience.graph.model.projects
import ch.datascience.interpreters.TestLogger
import ch.datascience.metrics.{LabeledGauge, TestLabeledHistogram}
import eu.timepit.refined.auto._
import io.renku.eventlog.EventContentGenerators.{eventDates, eventMessages, executionDates}
import io.renku.eventlog._
import io.renku.eventlog.statuschange.ChangeStatusRequest.EventOnlyRequest
import io.renku.eventlog.statuschange.CommandFindingResult.{CommandFound, NotSupported, PayloadMalformed}
import io.renku.eventlog.statuschange.StatusUpdatesRunnerImpl
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.time.temporal.ChronoUnit.MINUTES

class ToGenerationRecoverableFailureSpec
    extends AnyWordSpec
    with InMemoryEventLogDbSpec
    with MockFactory
    with should.Matchers {

  "command" should {

    s"set status $GenerationRecoverableFailure on the event with the given id and $GeneratingTriples status, " +
      "increment waiting events gauge and decrement under processing gauge for the project, insert the processingTime " +
      s"and return ${UpdateResult.Updated}" in new TestCase {

        storeEvent(
          compoundEventIds.generateOne.copy(id = eventId.id),
          EventStatus.GeneratingTriples,
          executionDates.generateOne,
          eventDates.generateOne,
          eventBodies.generateOne,
          batchDate = eventBatchDate
        )
        val executionDate = executionDates.generateOne
        val projectPath   = projectPaths.generateOne

        storeEvent(
          eventId,
          EventStatus.GeneratingTriples,
          executionDate,
          eventDates.generateOne,
          eventBodies.generateOne,
          batchDate = eventBatchDate,
          projectPath = projectPath
        )

        findEvent(eventId) shouldBe Some((executionDate, GeneratingTriples, None))

        (awaitingTriplesGenerationGauge.increment _).expects(projectPath).returning(IO.unit)
        (underTriplesGenerationGauge.decrement _).expects(projectPath).returning(IO.unit)

        val message = eventMessages.generateOne
        val command = ToGenerationRecoverableFailure[IO](eventId,
                                                         message,
                                                         awaitingTriplesGenerationGauge,
                                                         underTriplesGenerationGauge,
                                                         processingTime,
                                                         currentTime
        )

        (commandRunner run command).unsafeRunSync() shouldBe UpdateResult.Updated

        findEvent(eventId) shouldBe Some(
          (ExecutionDate(now.plus(10, MINUTES)), GenerationRecoverableFailure, Some(message))
        )
        findProcessingTime(eventId).eventIdsOnly shouldBe List(eventId)

        histogram.verifyExecutionTimeMeasured(command.queries.map(_.name))
      }

    EventStatus.all.filterNot(status => status == GeneratingTriples) foreach { eventStatus =>
      s"do nothing when updating event with $eventStatus status " +
        s"and return ${UpdateResult.Failure}" in new TestCase {

          val executionDate = executionDates.generateOne
          storeEvent(eventId,
                     eventStatus,
                     executionDate,
                     eventDates.generateOne,
                     eventBodies.generateOne,
                     batchDate = eventBatchDate
          )

          findEvent(eventId) shouldBe Some((executionDate, eventStatus, None))

          val message = eventMessages.generateOne
          val command = ToGenerationRecoverableFailure[IO](eventId,
                                                           message,
                                                           awaitingTriplesGenerationGauge,
                                                           underTriplesGenerationGauge,
                                                           processingTime,
                                                           currentTime
          )

          (commandRunner run command).unsafeRunSync() shouldBe a[UpdateResult.Failure]

          findEvent(eventId)          shouldBe Some((executionDate, eventStatus, None))
          findProcessingTime(eventId) shouldBe List()

          histogram.verifyExecutionTimeMeasured(command.queries.head.name)
        }
      s"do nothing when updating event with $eventStatus status " +
        s"and return ${UpdateResult.NotFound}" in new TestCase {

          findEvent(eventId) shouldBe None

          val command = ToGenerationRecoverableFailure[IO](eventId,
                                                           eventMessages.generateOne,
                                                           awaitingTriplesGenerationGauge,
                                                           underTriplesGenerationGauge,
                                                           processingTime,
                                                           currentTime
          )

          (commandRunner run command).unsafeRunSync() shouldBe UpdateResult.NotFound

          findEvent(eventId)          shouldBe None
          findProcessingTime(eventId) shouldBe List()
        }
    }

    "factory" should {
      "return a CommandFound when the change status request is acceptable" in new TestCase {
        val message             = eventMessages.generateOne
        val maybeProcessingTime = eventProcessingTimes.generateOption

        val actual = ToGenerationRecoverableFailure
          .factory[IO](awaitingTriplesGenerationGauge, underTriplesGenerationGauge)
          .run(EventOnlyRequest(eventId, GenerationRecoverableFailure, maybeProcessingTime, Some(message)))

        actual.unsafeRunSync() shouldBe CommandFound(
          ToGenerationRecoverableFailure(eventId,
                                         message,
                                         awaitingTriplesGenerationGauge,
                                         underTriplesGenerationGauge,
                                         maybeProcessingTime
          )
        )
      }

      "return a PayloadMalformed when status change request does not have a message" in new TestCase {
        ToGenerationRecoverableFailure
          .factory[IO](awaitingTriplesGenerationGauge, underTriplesGenerationGauge)
          .run(EventOnlyRequest(eventId, GenerationRecoverableFailure, eventProcessingTimes.generateOption, None))
          .unsafeRunSync() shouldBe PayloadMalformed("No message provided")
      }

      EventStatus.all.filterNot(status => status == GenerationRecoverableFailure) foreach { eventStatus =>
        s"return NotSupported if the decoding failed with status: $eventStatus " in new TestCase {
          ToGenerationRecoverableFailure
            .factory[IO](awaitingTriplesGenerationGauge, underTriplesGenerationGauge)
            .run(
              EventOnlyRequest(eventId, eventStatus, eventProcessingTimes.generateOption, eventMessages.generateOption)
            )
            .unsafeRunSync() shouldBe NotSupported
        }
      }
    }
  }

  private trait TestCase {
    val awaitingTriplesGenerationGauge = mock[LabeledGauge[IO, projects.Path]]
    val underTriplesGenerationGauge    = mock[LabeledGauge[IO, projects.Path]]
    val histogram                      = TestLabeledHistogram[SqlStatement.Name]("query_id")
    val currentTime                    = mockFunction[Instant]
    val eventId                        = compoundEventIds.generateOne
    val eventBatchDate                 = batchDates.generateOne
    val processingTime                 = eventProcessingTimes.generateSome
    val commandRunner                  = new StatusUpdatesRunnerImpl(sessionResource, histogram, TestLogger[IO]())
    val now                            = Instant.now()

    currentTime.expects().returning(now).anyNumberOfTimes()
  }

}
