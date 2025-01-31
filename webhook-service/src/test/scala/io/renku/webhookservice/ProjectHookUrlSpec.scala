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

package io.renku.webhookservice

import WebhookServiceGenerators._
import com.typesafe.config.ConfigFactory
import io.renku.generators.Generators.Implicits._
import model.ProjectHookUrl
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

class ProjectHookUrlSpec extends AnyWordSpec with MockFactory with should.Matchers {

  "fromConfig" should {

    "return project hook url composed of selfUrl and /webhooks/events " +
      "if selfUrl can be obtained" in {
        val selfUrl = selfUrls.generateOne
        val config = ConfigFactory.parseMap(
          Map(
            "services" -> Map(
              "self" -> Map(
                "url" -> selfUrl.toString()
              ).asJava
            ).asJava
          ).asJava
        )

        ProjectHookUrl.fromConfig[Try](config).map(_.value) shouldBe Success(
          s"$selfUrl/webhooks/events"
        )
      }

    "fail if finding selfUrl fails" in {
      ProjectHookUrl.fromConfig[Try](ConfigFactory.empty()) shouldBe a[Failure[_]]
    }
  }
}
