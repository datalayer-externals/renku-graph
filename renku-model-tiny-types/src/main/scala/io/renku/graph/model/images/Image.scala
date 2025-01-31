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

package io.renku.graph.model.images

import io.renku.graph.model.Schemas.schema
import io.renku.graph.model.projects
import io.renku.jsonld._
import io.renku.jsonld.ontology._
import io.renku.jsonld.syntax._

final case class Image(resourceId: ImageResourceId, uri: ImageUri, position: ImagePosition)

object Image {

  def projectImage(projectId: projects.ResourceId, uris: List[ImageUri]): List[Image] =
    uris.zipWithIndex.map { case (uri, index) =>
      Image(ImageResourceId((projectId / "images" / index.toString).value), uri, ImagePosition(index))
    }

  def projectImage(projectId: projects.ResourceId, uri: ImageUri): Image =
    projectImage(projectId, List(uri)).head

  private val imageEntityTypes = EntityTypes of schema / "ImageObject"

  implicit val jsonLDEncoder: JsonLDEncoder[Image] = JsonLDEncoder.instance { case Image(resourceId, uri, position) =>
    JsonLD.entity(
      resourceId.asEntityId,
      imageEntityTypes,
      (schema / "contentUrl") -> uri.asJsonLD,
      (schema / "position")   -> position.asJsonLD
    )
  }

  implicit lazy val decoder: JsonLDDecoder[Image] = JsonLDDecoder.entity(imageEntityTypes) { cursor =>
    for {
      resourceId <- cursor.downEntityId.as[ImageResourceId]
      uri        <- cursor.downField(schema / "contentUrl").as[ImageUri]
      position   <- cursor.downField(schema / "position").as[ImagePosition]
    } yield Image(resourceId, uri, position)
  }

  object Ontology {

    val typeClass:          Class            = Class(schema / "ImageObject")
    val contentUrlProperty: DataProperty.Def = DataProperty(schema / "contentUrl", xsd / "string")
    val positionProperty:   DataProperty.Def = DataProperty(schema / "position", xsd / "int")

    val typeDef: Type = Type.Def(
      typeClass,
      contentUrlProperty,
      positionProperty
    )
  }
}
