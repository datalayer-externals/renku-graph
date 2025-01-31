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

package io.renku.events.consumers

import io.circe.{Decoder, DecodingFailure, Json}
import io.renku.events.CategoryName
import io.renku.graph.model.events.{CompoundEventId, EventId}
import io.renku.graph.model.projects
import io.renku.json.JsonOps.JsonExt

object EventDecodingTools extends EventDecodingTools

trait EventDecodingTools {

  implicit class JsonOps(override val json: Json) extends JsonExt {

    import io.renku.tinytypes.json.TinyTypeDecoders._

    lazy val categoryName: Either[DecodingFailure, CategoryName] =
      json.hcursor
        .downField("categoryName")
        .as[CategoryName]

    lazy val getProject: Either[DecodingFailure, Project] = json.as[Project]

    lazy val getEventId: Either[DecodingFailure, CompoundEventId] = json.as[CompoundEventId]

    lazy val getProjectSlug: Either[DecodingFailure, projects.Slug] =
      json.hcursor.downField("project").downField("slug").as[projects.Slug]

    private implicit val projectDecoder: Decoder[Project] = { implicit cursor =>
      for {
        projectId   <- cursor.downField("project").downField("id").as[projects.GitLabId]
        projectSlug <- cursor.downField("project").downField("slug").as[projects.Slug]
      } yield Project(projectId, projectSlug)
    }

    private implicit val eventIdDecoder: Decoder[CompoundEventId] = { implicit cursor =>
      for {
        id        <- cursor.downField("id").as[EventId]
        projectId <- cursor.downField("project").downField("id").as[projects.GitLabId]
      } yield CompoundEventId(id, projectId)
    }
  }
}
