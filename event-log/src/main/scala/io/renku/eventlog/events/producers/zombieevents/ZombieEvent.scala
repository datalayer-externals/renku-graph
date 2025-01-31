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

package io.renku.eventlog.events.producers.zombieevents

import cats.Show
import cats.implicits.showInterpolator
import io.renku.graph.model.events.CompoundEventId
import io.renku.graph.model.events.EventStatus.ProcessingStatus
import io.renku.graph.model.projects
import io.renku.tinytypes.{StringTinyType, TinyTypeFactory}

private final class ZombieEventProcess private (val value: String) extends AnyVal with StringTinyType
private object ZombieEventProcess extends TinyTypeFactory[ZombieEventProcess](new ZombieEventProcess(_))

private case class ZombieEvent(generatedBy: ZombieEventProcess,
                               eventId:     CompoundEventId,
                               projectSlug: projects.Slug,
                               status:      ProcessingStatus
) {
  override lazy val toString: String =
    s"$ZombieEvent $generatedBy $eventId, projectSlug = $projectSlug, status = $status"
}

private object ZombieEvent {
  implicit lazy val show: Show[ZombieEvent] = Show.show(event =>
    show"${event.generatedBy} ${event.eventId}, projectSlug = ${event.projectSlug}, status = ${event.status}"
  )
}

private object ZombieEventEncoder {

  import io.circe.Json
  import io.circe.literal.JsonStringContext

  def encodeEvent(event: ZombieEvent): Json = json"""{
    "categoryName": $categoryName,
    "id":           ${event.eventId.id},
    "project": {
      "id":   ${event.eventId.projectId},
      "slug": ${event.projectSlug}
    },
    "status": ${event.status}
  }"""
}
