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

package io.renku.triplesgenerator.events.consumers.awaitinggeneration

import io.circe._
import io.circe.literal._
import io.renku.events.EventRequestContent
import io.renku.events.consumers.Project
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.jsons
import io.renku.graph.model.EventsGenerators._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.graph.model.events.EventId
import org.scalatest.EitherValues
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class EventDecoderSpec extends AnyWordSpec with should.Matchers with EitherValues {

  "toCommitEvents" should {

    "produce a CommitEvent if the Json string can be successfully deserialized" in new TestCase {
      EventDecoder
        .decode(EventRequestContent.WithPayload(jsons.generateOne, eventBody))
        .value shouldBe CommitEvent(EventId(commitId.value), Project(projectId, projectPath), commitId)
    }

    "fail if parsing fails" in new TestCase {

      val result = EventDecoder.decode(EventRequestContent.WithPayload(jsons.generateOne, "{"))

      result.left.value                                         shouldBe a[ParsingFailure]
      result.left.value.getMessage                              shouldBe "CommitEvent cannot be decoded: '{'"
      result.left.value.asInstanceOf[ParsingFailure].underlying shouldBe a[ParsingFailure]
    }

    "fail if decoding fails" in new TestCase {

      val result = EventDecoder.decode(EventRequestContent.WithPayload(jsons.generateOne, "{}"))

      result.left.value                                       shouldBe a[DecodingFailure]
      result.left.value.asInstanceOf[DecodingFailure].message shouldBe "CommitEvent cannot be decoded: '{}'"
    }
  }

  private trait TestCase {
    val commitId    = commitIds.generateOne
    val projectId   = projectIds.generateOne
    val projectPath = projectPaths.generateOne

    lazy val eventBody: String = json"""{
      "id": $commitId,
      "parents": ${commitIds.generateList()},
      "project": {
        "id":   $projectId,
        "path": $projectPath
      }
    }""".noSpaces
  }
}
