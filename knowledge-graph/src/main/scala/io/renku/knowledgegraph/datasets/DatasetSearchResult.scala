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

package io.renku.knowledgegraph
package datasets

import DatasetSearchResult.{ExemplarProject, ProjectsCount}
import cats.syntax.all._
import io.circe.literal._
import io.circe.{Encoder, Json}
import io.renku.config
import io.renku.graph.model.datasets.{CreatedOrPublished, DatePublished, Description, Identifier, Keyword, Name, Title}
import io.renku.graph.model.images.ImageUri
import io.renku.graph.model.{projects, GitLabUrl}
import io.renku.http.rest.Links.{_links, Href, Link, Rel}
import io.renku.json.JsonOps._
import io.renku.knowledgegraph.datasets.details.RequestedDataset
import io.renku.tinytypes.constraints.NonNegativeInt
import io.renku.tinytypes.{IntTinyType, TinyTypeFactory}

final case class DatasetSearchResult(
    id:                 Identifier,
    title:              Title,
    name:               Name,
    maybeDescription:   Option[Description],
    creators:           List[DatasetCreator],
    createdOrPublished: CreatedOrPublished,
    exemplarProject:    ExemplarProject,
    projectsCount:      ProjectsCount,
    keywords:           List[Keyword],
    images:             List[ImageUri]
)

object DatasetSearchResult {

  final case class ExemplarProject(id: projects.ResourceId, slug: projects.Slug)

  implicit def encoder(implicit gitLabUrl: GitLabUrl, renkuApiUrl: config.renku.ApiUrl): Encoder[DatasetSearchResult] =
    Encoder.instance[DatasetSearchResult] {
      case DatasetSearchResult(id,
                               title,
                               name,
                               maybeDescription,
                               creators,
                               date,
                               exemplarProject,
                               projectsCount,
                               keywords,
                               images
          ) =>
        json"""{
        "identifier":    $id,
        "title":         $title,
        "name":          $name,
        "slug":          $name,
        "published":     ${creators -> date},
        "date":          ${date.instant},
        "projectsCount": $projectsCount,
        "keywords":      $keywords,
        "images":        ${images -> exemplarProject}
      }"""
          .addIfDefined("description" -> maybeDescription)
          .deepMerge(_links(Link(Rel("details") -> datasets.details.Endpoint.href(renkuApiUrl, RequestedDataset(id)))))
    }

  private implicit lazy val publishingEncoder: Encoder[(List[DatasetCreator], CreatedOrPublished)] = Encoder.instance {
    case (creators, DatePublished(date)) => json"""{
    "creator":       $creators,
    "datePublished": $date
  }"""
    case (creators, _) => json"""{
    "creator": $creators
  }"""
  }

  private implicit lazy val creatorEncoder: Encoder[DatasetCreator] = Encoder.instance[DatasetCreator] {
    case DatasetCreator(maybeEmail, name, _) => json"""{
    "name": $name
  }""" addIfDefined ("email" -> maybeEmail)
  }

  private implicit def imagesEncoder(implicit gitLabUrl: GitLabUrl): Encoder[(List[ImageUri], ExemplarProject)] =
    Encoder.instance[(List[ImageUri], ExemplarProject)] { case (imageUris, ExemplarProject(_, projectSlug)) =>
      Json.arr(imageUris.map {
        case uri: ImageUri.Relative =>
          json"""{
          "location": $uri
        }""" deepMerge _links(
            Link(Rel("view") -> Href(gitLabUrl / projectSlug / "raw" / "master" / uri))
          )
        case uri: ImageUri.Absolute =>
          json"""{
          "location": $uri  
        }""" deepMerge _links(
            Link(Rel("view") -> Href(uri.show))
          )
      }: _*)
    }

  final class ProjectsCount private (val value: Int) extends AnyVal with IntTinyType
  implicit object ProjectsCount
      extends TinyTypeFactory[ProjectsCount](new ProjectsCount(_))
      with NonNegativeInt[ProjectsCount]
}
