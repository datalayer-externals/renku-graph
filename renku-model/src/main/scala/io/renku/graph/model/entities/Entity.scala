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

package io.renku.graph.model.entities

import io.renku.cli.model.{CliCollectionEntity, CliEntity, CliSingleEntity}
import io.renku.graph.model.Schemas.{prov, renku}
import io.renku.graph.model.entityModel._
import io.renku.graph.model.generations
import io.renku.jsonld.ontology._
import io.renku.jsonld.EntityTypes

sealed trait Entity {
  val resourceId: ResourceId
  val location:   Location
  val checksum:   Checksum
}

object Entity {

  final case class InputEntity(resourceId: ResourceId, location: Location, checksum: Checksum) extends Entity

  final case class OutputEntity(resourceId:            ResourceId,
                                location:              Location,
                                checksum:              Checksum,
                                generationResourceIds: List[generations.ResourceId]
  ) extends Entity

  def fromCli(entity: CliEntity): Entity = entity.fold(fromCli, fromCli)

  def fromCli(entity: CliSingleEntity): Entity = entity.generationIds match {
    case Nil => InputEntity(entity.resourceId, Location.File(entity.path.value), entity.checksum)
    case generationIds =>
      OutputEntity(entity.resourceId, Location.File(entity.path.value), entity.checksum, generationIds)
  }

  def fromCli(entity: CliCollectionEntity): Entity = entity.generationIds match {
    case Nil => InputEntity(entity.resourceId, Location.Folder(entity.path.value), entity.checksum)
    case generationIds =>
      OutputEntity(entity.resourceId, Location.Folder(entity.path.value), entity.checksum, generationIds)
  }

  import io.renku.jsonld.JsonLDEncoder

  val fileEntityTypes:   EntityTypes = EntityTypes of (prov / "Entity")
  val folderEntityTypes: EntityTypes = EntityTypes of (prov / "Entity", prov / "Collection")

  implicit def encoder[E <: Entity]: JsonLDEncoder[E] = {
    import io.renku.graph.model.Schemas._
    import io.renku.jsonld._
    import io.renku.jsonld.syntax._

    lazy val toEntityTypes: Entity => EntityTypes = { entity =>
      entity.location match {
        case Location.File(_)   => fileEntityTypes
        case Location.Folder(_) => folderEntityTypes
      }
    }

    JsonLDEncoder.instance {
      case entity @ InputEntity(resourceId, location, checksum) =>
        JsonLD.entity(
          resourceId.asEntityId,
          toEntityTypes(entity),
          prov / "atLocation" -> location.asJsonLD,
          renku / "checksum"  -> checksum.asJsonLD
        )
      case entity @ OutputEntity(resourceId, location, checksum, generationResourceIds) =>
        JsonLD.entity(
          resourceId.asEntityId,
          toEntityTypes(entity),
          prov / "atLocation"          -> location.asJsonLD,
          renku / "checksum"           -> checksum.asJsonLD,
          prov / "qualifiedGeneration" -> generationResourceIds.map(_.asEntityId).asJsonLD
        )
    }
  }

  lazy val ontology: Type = Type.Def(
    Class(prov / "Entity"),
    ObjectProperties(ObjectProperty(prov / "qualifiedGeneration", Generation.ontology)),
    DataProperties(DataProperty(prov / "atLocation", xsd / "string"), DataProperty(renku / "checksum", xsd / "string"))
  )
}
