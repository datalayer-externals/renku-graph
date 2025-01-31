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

package io.renku.graph.acceptancetests.knowledgegraph

import io.circe.{Encoder, Json}
import io.circe.literal._
import io.renku.graph.model.images.ImageUri
import io.renku.graph.model.{GitLabUrl, projects}

trait ImageApiEncoders {
  def gitLabUrl: GitLabUrl

  implicit def imagesEncoder: Encoder[(List[ImageUri], projects.Slug)] =
    Encoder.instance[(List[ImageUri], projects.Slug)] { case (images, exemplarProjectSlug) =>
      Json.arr(images.map {
        case uri: ImageUri.Relative => json"""{
           "_links": [{
             "rel": "view",
             "href": ${s"$gitLabUrl/$exemplarProjectSlug/raw/master/$uri"}
           }],
           "location": $uri
         }"""
        case uri: ImageUri.Absolute => json"""{
           "_links": [{
             "rel": "view",
             "href": $uri
           }],
           "location": $uri
         }"""
      }: _*)
    }
}
