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

package io.renku.graph.model

import cats.syntax.all._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import org.scalacheck.Gen
import org.scalatest.TryValues
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.Try

class ResourceIdSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with should.Matchers
    with TryValues
    with RenkuTinyTypeGenerators {

  implicit val renkuUrl: RenkuUrl = RenkuTinyTypeGenerators.renkuUrls.generateOne

  "toIdentifier converted" should {

    "be successful for valid ResourceIds" in {
      forAll(planIdentifiers) { id =>
        val resourceId = plans.ResourceId(id)
        resourceId.as[Try, plans.Identifier].success.value shouldBe id
      }
    }

    "fail for an unknown resourceIds" in {
      val resourceId = plans.ResourceId {
        httpUrls(
          pathGenerator =
            relativePaths(partsGenerator = Gen.frequency(9 -> planIdentifiers.generateOne.show, 1 -> nonEmptyStrings()))
        ).generateOne
      }

      val exception = resourceId.as[Try, plans.Identifier].failure.exception

      exception            shouldBe an[IllegalArgumentException]
      exception.getMessage shouldBe s"'${resourceId.value}' cannot be converted to a plans.Identifier"
    }
  }
}
