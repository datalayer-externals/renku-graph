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

package io.renku.triplesgenerator.events.consumers.tsmigrationrequest.migrations.tooling

import io.circe.literal._
import io.renku.events.CategoryName
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators.projectSlugs
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class CleanUpEventsProducerSpec extends AnyWordSpec with should.Matchers {

  "CleanUpEventsProducer" should {

    "transform the given ProjectSlug to CleanUpEvent request" in {

      val slug = projectSlugs.generateOne

      val (actualSlug, eventRequest, categoryName) = CleanUpEventsProducer(slug)

      actualSlug   shouldBe slug
      categoryName shouldBe CategoryName("TS_MIGRATION_REQUEST")
      eventRequest.event shouldBe json"""{
        "categoryName": "CLEAN_UP_REQUEST",
        "project": {
          "slug": $slug
        }
      }"""
    }
  }
}
