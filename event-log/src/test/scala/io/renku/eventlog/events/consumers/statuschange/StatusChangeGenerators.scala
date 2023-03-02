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

import io.renku.eventlog.events.consumers.statuschange.StatusChangeEvent._
import io.renku.events.consumers.{ConsumersModelGenerators, Project}
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.events.EventStatus.{GenerationNonRecoverableFailure, GenerationRecoverableFailure, TransformationNonRecoverableFailure, TransformationRecoverableFailure}
import io.renku.graph.model.{EventContentGenerators, EventsGenerators}
import org.scalacheck.Gen

import java.time.{Duration => JDuration}

object StatusChangeGenerators {

  val projectEventsToNewEvents = ConsumersModelGenerators.consumerProjects.map(ProjectEventsToNew(_))

  lazy val rollbackToAwaitingDeletionEvents =
    ConsumersModelGenerators.consumerProjects.map(RollbackToAwaitingDeletion.apply)

  lazy val rollbackToNewEvents = for {
    id      <- EventsGenerators.eventIds
    project <- ConsumersModelGenerators.consumerProjects
  } yield RollbackToNew(id, project)

  lazy val rollbackToTriplesGeneratedEvents = for {
    id      <- EventsGenerators.eventIds
    project <- ConsumersModelGenerators.consumerProjects
  } yield RollbackToTriplesGenerated(id, project)

  lazy val toAwaitingDeletionEvents = for {
    id      <- EventsGenerators.eventIds
    project <- ConsumersModelGenerators.consumerProjects
  } yield ToAwaitingDeletion(id, project)

  lazy val toFailureEvents = for {
    eventId        <- EventsGenerators.compoundEventIds
    projectPath    <- projectPaths
    message        <- EventContentGenerators.eventMessages
    executionDelay <- executionDelays.toGeneratorOfOptions
    failure <- Gen.oneOf(GenerationRecoverableFailure,
                         GenerationNonRecoverableFailure,
                         TransformationRecoverableFailure,
                         TransformationNonRecoverableFailure
               )
  } yield ToFailure(eventId.id, Project(eventId.projectId, projectPath), message, failure, executionDelay)

  private def executionDelays: Gen[JDuration] = Gen.choose(0L, 10L).map(JDuration.ofSeconds)

  lazy val toTriplesGeneratedEvents = for {
    id             <- EventsGenerators.eventIds
    project        <- ConsumersModelGenerators.consumerProjects
    processingTime <- EventsGenerators.eventProcessingTimes
    payload        <- EventsGenerators.zippedEventPayloads
  } yield ToTriplesGenerated(id, project, processingTime, payload)

  lazy val toTripleStoreEvents = for {
    id             <- EventsGenerators.eventIds
    project        <- ConsumersModelGenerators.consumerProjects
    processingTime <- EventsGenerators.eventProcessingTimes
  } yield ToTriplesStore(id, project, processingTime)

  def statusChangeEvents: Gen[StatusChangeEvent] =
    Gen.oneOf(
      toTripleStoreEvents,
      toTriplesGeneratedEvents,
      toFailureEvents,
      toAwaitingDeletionEvents,
      rollbackToTriplesGeneratedEvents,
      rollbackToNewEvents,
      rollbackToAwaitingDeletionEvents,
      projectEventsToNewEvents
      // TODO add missing!
    )
}
