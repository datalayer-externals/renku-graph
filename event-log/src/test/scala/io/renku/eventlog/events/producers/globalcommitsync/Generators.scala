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

package io.renku.eventlog.events.producers.globalcommitsync

import GlobalCommitSyncEvent.{CommitsCount, CommitsInfo}
import io.renku.events.consumers.ConsumersModelGenerators._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.positiveLongs
import io.renku.graph.model.EventsGenerators.{commitIds, lastSyncedDates}
import org.scalacheck.Gen

private object Generators {

  val globalCommitSyncEvents: Gen[GlobalCommitSyncEvent] = for {
    project             <- consumerProjects
    commitsCount        <- positiveLongs().map(_.value).toGeneratorOf(CommitsCount)
    latestCommit        <- commitIds
    currentLastSyncDate <- lastSyncedDates.toGeneratorOfOptions
  } yield GlobalCommitSyncEvent(project, CommitsInfo(commitsCount, latestCommit), currentLastSyncDate)
}
