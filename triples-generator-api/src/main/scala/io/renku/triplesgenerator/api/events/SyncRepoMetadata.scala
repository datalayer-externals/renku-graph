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

package io.renku.triplesgenerator.api.events

import cats.Show
import cats.syntax.all._
import io.circe.DecodingFailure.Reason.CustomReason
import io.circe.literal._
import io.circe.{Decoder, DecodingFailure, Encoder}
import io.renku.events.CategoryName
import io.renku.graph.model.projects

final case class SyncRepoMetadata(slug: projects.Slug)

object SyncRepoMetadata {

  val categoryName: CategoryName = CategoryName("SYNC_REPO_METADATA")

  implicit val eventEncoder: Encoder[SyncRepoMetadata] = Encoder.instance { case SyncRepoMetadata(slug) =>
    json"""{
      "categoryName": $categoryName,
      "project": {
        "slug": $slug
      }
    }"""
  }

  implicit val eventDecoder: Decoder[SyncRepoMetadata] = Decoder.instance { cursor =>
    import io.renku.tinytypes.json.TinyTypeDecoders._

    lazy val validateCategory = cursor.downField("categoryName").as[CategoryName] >>= {
      case `categoryName` => ().asRight
      case other          => DecodingFailure(CustomReason(s"Expected $categoryName but got $other"), cursor).asLeft
    }

    (validateCategory >> cursor.downField("project").downField("slug").as[projects.Slug])
      .map(SyncRepoMetadata(_))
  }

  implicit val show: Show[SyncRepoMetadata] = Show.show { case SyncRepoMetadata(slug) =>
    show"projectSlug = $slug"
  }
}
