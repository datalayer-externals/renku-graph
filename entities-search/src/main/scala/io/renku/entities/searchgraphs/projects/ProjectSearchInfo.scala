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

package io.renku.entities.searchgraphs.projects

import cats.Show
import cats.syntax.all._
import io.renku.graph.model.images.Image
import io.renku.graph.model.{persons, projects}

private final case class ProjectSearchInfo(id:               projects.ResourceId,
                                           name:             projects.Name,
                                           slug:             projects.Slug,
                                           visibility:       projects.Visibility,
                                           dateCreated:      projects.DateCreated,
                                           dateModified:     projects.DateModified,
                                           maybeCreator:     Option[persons.ResourceId],
                                           keywords:         List[projects.Keyword],
                                           maybeDescription: Option[projects.Description],
                                           images:           List[Image]
)

private object ProjectSearchInfo {

  implicit val show: Show[ProjectSearchInfo] = Show.show {
    case ProjectSearchInfo(id,
                           name,
                           slug,
                           visibility,
                           dateCreated,
                           dateModified,
                           maybeCreator,
                           keywords,
                           maybeDescription,
                           images
        ) =>
      List(
        show"id = $id".some,
        show"name = $name".some,
        show"slug = $slug".some,
        show"visibility = $visibility".some,
        show"dateCreated = $dateCreated".some,
        show"dateModified = $dateModified".some,
        maybeCreator.map(creator => show"creator = $creator"),
        keywords match {
          case Nil => None
          case k   => show"keywords = [${k.mkString(", ")}]".some
        },
        maybeDescription.map(d => show"description = $d"),
        images match {
          case Nil => None
          case i   => show"images = [${i.sortBy(_.position).map(i => show"${i.uri.value}").mkString(", ")}]".some
        }
      ).flatten.mkString(", ")
  }
}
