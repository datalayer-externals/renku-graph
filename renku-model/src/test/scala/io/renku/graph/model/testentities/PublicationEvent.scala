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

package io.renku.graph.model.testentities

import cats.syntax.all._
import io.renku.cli.model.CliPublicationEvent
import io.renku.graph.model._
import io.renku.graph.model.cli.CliConverters
import io.renku.graph.model.publicationEvents._
import io.renku.graph.model.testentities.Dataset.Provenance

final case class PublicationEvent(dataset:          Dataset[Provenance],
                                  maybeDescription: Option[Description],
                                  name:             Name,
                                  startDate:        StartDate
)

object PublicationEvent {

  import io.renku.jsonld._
  import io.renku.jsonld.syntax._

  implicit def toEntitiesPublicationEvent(implicit
      renkuUrl: RenkuUrl
  ): PublicationEvent => entities.PublicationEvent = publicationEvent =>
    entities.PublicationEvent(
      publicationEvents.ResourceId(publicationEvent.asEntityId.show),
      About((renkuUrl / "urls" / "datasets" / publicationEvent.dataset.identifier).show),
      datasets.ResourceId(publicationEvent.dataset.asEntityId.show),
      publicationEvent.maybeDescription,
      publicationEvent.name,
      publicationEvent.startDate
    )

  implicit def toCliPublicationEvent(implicit renkuUrl: RenkuUrl): PublicationEvent => CliPublicationEvent =
    CliConverters.from(_)

  implicit def encoder(implicit renkuUrl: RenkuUrl): JsonLDEncoder[PublicationEvent] =
    JsonLDEncoder.instance(_.to[entities.PublicationEvent].asJsonLD)

  implicit def entityIdEncoder(implicit renkuUrl: RenkuUrl): EntityIdEncoder[PublicationEvent] =
    EntityIdEncoder.instance(event => renkuUrl / "datasettags" / s"${event.name}@${event.dataset.asEntityId.show}")
}
