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

package io.renku.entities.viewings.collector
package projects

import io.renku.graph.model.{projects, GraphClass}
import io.renku.jsonld._
import io.renku.jsonld.syntax._

private object Encoder {

  final case class ProjectViewing(projectId: projects.ResourceId, dateViewed: projects.DateViewed)

  def encode(viewing: ProjectViewing): NamedGraph =
    NamedGraph.fromJsonLDsUnsafe(
      GraphClass.ProjectViewedTimes.id,
      viewing.asJsonLD
    )

  private implicit lazy val projectViewingEncoder: JsonLDEncoder[ProjectViewing] =
    JsonLDEncoder.instance { case ProjectViewing(entityId, date) =>
      JsonLD.entity(
        entityId.asEntityId,
        EntityTypes of ProjectViewedTimeOntology.classType,
        ProjectViewedTimeOntology.dataViewedProperty.id -> date.asJsonLD
      )
    }
}
