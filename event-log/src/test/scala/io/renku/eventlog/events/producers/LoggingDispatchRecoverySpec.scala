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

package io.renku.eventlog.events.producers

import Generators._
import TestCategoryEvent.testCategoryEvents
import cats.effect._
import cats.syntax.all._
import io.renku.events.Generators.{categoryNames, subscriberUrls}
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.exceptions
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Error
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class LoggingDispatchRecoverySpec extends AnyWordSpec with IOSpec with should.Matchers with MockFactory {

  "recover" should {

    "log an error" in new TestCase {
      val exception  = exceptions.generateOne
      val subscriber = subscriberUrls.generateOne

      recovery.unsafeRunSync().recover(subscriber, event)(exception).unsafeRunSync() shouldBe ()

      logger.loggedOnly(
        Error(s"$categoryName: $event, url = $subscriber failed", exception)
      )
    }
  }

  "returnToQueue" should {
    "return unit" in new TestCase {
      recovery.unsafeRunSync().returnToQueue(event, sendingResults.generateOne) shouldBe ().pure[IO]
    }
  }

  private trait TestCase {
    val event = testCategoryEvents.generateOne

    val categoryName = categoryNames.generateOne
    protected implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val recovery = LoggingDispatchRecovery[IO, TestCategoryEvent](categoryName)
  }
}
