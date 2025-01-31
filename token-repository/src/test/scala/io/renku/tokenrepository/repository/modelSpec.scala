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

package io.renku.tokenrepository.repository

import cats.syntax.all._
import com.typesafe.config.ConfigFactory
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.{ints, nonEmptyStrings}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Period
import scala.jdk.CollectionConverters._
import scala.util.{Success, Try}

class ProjectTokenDuePeriodSpec extends AnyWordSpec with should.Matchers with ScalaCheckPropertyChecks {

  "apply" should {

    "read 'project-token-due-period' as Period from the config" in {
      forAll(ints(min = 1, max = 2 * 365)) { duration =>
        val config = ConfigFactory.parseMap(
          Map("project-token-due-period" -> s"$duration days").asJava
        )

        ProjectTokenDuePeriod[Try](config) shouldBe Period.ofDays(duration).pure[Try]
      }
    }
  }
}

class RenkuAccessTokenNameSpec extends AnyWordSpec with should.Matchers {

  "apply" should {

    "read 'project-token-name' as Period from the config" in {
      val name = nonEmptyStrings().generateOne
      val config = ConfigFactory.parseMap(
        Map("project-token-name" -> name).asJava
      )

      val Success(actual) = RenkuAccessTokenName[Try](config)

      actual       shouldBe a[RenkuAccessTokenName]
      actual.value shouldBe name
    }
  }
}
