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

package io.renku.entities.viewings.collector.projects.activated

import cats.effect.IO
import cats.syntax.all._
import io.circe.syntax._
import io.renku.events.EventRequestContent
import io.renku.events.consumers.ProcessExecutor
import io.renku.generators.Generators.Implicits._
import io.renku.interpreters.TestLogger
import io.renku.testtools.IOSpec
import io.renku.triplesgenerator.api.events.Generators._
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

class EventHandlerSpec extends AnyWordSpec with should.Matchers with IOSpec with MockFactory with EitherValues {

  "handlingDefinition.decode" should {

    "decode the Project slug from the event" in new TestCase {
      handler
        .createHandlingDefinition()
        .decode(EventRequestContent.NoPayload(event.asJson))
        .value shouldBe event
    }
  }

  "handlingDefinition.process" should {

    "be the TSUploader.uploadToTS" in new TestCase {

      (tsPersister.persist _).expects(event).returns(().pure[IO])

      handler.createHandlingDefinition().process(event).unsafeRunSync() shouldBe ()
    }
  }

  "handlingDefinition.precondition" should {

    "be not defined" in new TestCase {
      handler.createHandlingDefinition().precondition.unsafeRunSync() shouldBe None
    }
  }

  "handlingDefinition.onRelease" should {

    "be not defined" in new TestCase {
      handler.createHandlingDefinition().onRelease shouldBe None
    }
  }

  private trait TestCase {

    val event = projectActivatedEvents.generateOne

    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val tsPersister = mock[EventPersister[IO]]
    val handler     = new EventHandler[IO](tsPersister, mock[ProcessExecutor[IO]])
  }
}
