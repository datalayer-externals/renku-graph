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

package io.renku.knowledgegraph.projects.details

import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.GraphModelGenerators._
import model.Permissions.AccessLevel
import model.Permissions.AccessLevel._
import model.Urls.SshUrl
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class modelSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  "SshUrl" should {

    "instantiate for valid absolute ssh urls" in {
      forAll(nonEmptyList(nonBlankStrings()), projectSlugs) { (hostParts, projectSlug) =>
        val url = s"git@${hostParts.toList.mkString(".")}:$projectSlug.git"
        SshUrl.from(url).map(_.value) shouldBe Right(url)
      }
    }

    "fail instantiation for non-ssh urls" in {
      val url = s"${gitLabUrls.generateOne}/${projectSlugs.generateOne}.git"

      val Left(exception) = SshUrl.from(url)

      exception            shouldBe an[IllegalArgumentException]
      exception.getMessage shouldBe s"$url is not a valid repository ssh url"
    }
  }

  "AccessLevel.from" should {

    val scenarios = Table(
      ("level", "expected AccessLevel", "expected name"),
      (10, Guest, "Guest"),
      (20, Reporter, "Reporter"),
      (30, Developer, "Developer"),
      (40, Maintainer, "Maintainer"),
      (50, Owner, "Owner")
    )
    forAll(scenarios) { (level, expectedInstance, expectedName) =>
      s"return $expectedInstance for $level level" in {
        AccessLevel.from(level) shouldBe Right(expectedInstance)

        expectedInstance.name.value  shouldBe expectedName
        expectedInstance.value.value shouldBe level
      }
    }

    "return an exception for an unknown level" in {
      val Left(exception) = AccessLevel.from(0)

      exception            shouldBe an[IllegalArgumentException]
      exception.getMessage shouldBe "Unrecognized AccessLevel with value '0'"
    }
  }

  "AccessLevel.toString" should {

    "return concatenated value of name and value" in {
      AccessLevel.all foreach { level =>
        level.toString shouldBe s"${level.name} (${level.value})"
      }
    }
  }
}
