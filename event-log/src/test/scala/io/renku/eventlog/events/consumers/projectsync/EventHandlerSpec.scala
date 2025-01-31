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

package io.renku.eventlog.events.consumers
package projectsync

import cats.effect.IO
import io.circe.{Encoder, Json}
import io.circe.literal._
import io.circe.syntax._
import io.renku.events.EventRequestContent
import io.renku.events.consumers.ProcessExecutor
import io.renku.events.consumers.subscriptions.SubscriptionMechanism
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators._
import io.renku.interpreters.TestLogger
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class EventHandlerSpec
    extends AnyWordSpec
    with IOSpec
    with MockFactory
    with should.Matchers
    with Eventually
    with IntegrationPatience {

  "createHandlingDefinition.decode" should {
    s"decode a valid event successfully" in new TestCase {
      val definition = handler.createHandlingDefinition()
      val eventData  = EventRequestContent(event.asJson)
      definition.decode(eventData) shouldBe Right(event)
    }

    "fail on invalid event data" in new TestCase {
      val definition = handler.createHandlingDefinition()
      val eventData  = Json.obj("invalid" -> true.asJson)
      definition.decode(EventRequestContent(eventData)).isLeft shouldBe true
    }
  }

  "createHandlingDefinition.process" should {
    "call to StatusUpdater" in new TestCase {
      val definition = handler.createHandlingDefinition()
      (synchronizer.syncProjectInfo _).expects(*).returning(IO.unit)
      definition.process(event).unsafeRunSync() shouldBe ()
    }
  }

  "createHandlingDefinition" should {
    "not define  precondition" in new TestCase {
      val definition = handler.createHandlingDefinition()
      definition.precondition.unsafeRunSync() shouldBe None
    }

    "onRelease call to renewSubscription" in new TestCase {
      val definition = handler.createHandlingDefinition()
      definition.onRelease.map(_.unsafeRunSync()).getOrElse(sys.error("No onRelease defined")) shouldBe ()
    }
  }

  private trait TestCase {
    val event = projectSyncEvents.generateOne

    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val synchronizer          = mock[ProjectInfoSynchronizer[IO]]
    val subscriptionMechanism = mock[SubscriptionMechanism[IO]]

    val handler = new EventHandler[IO](synchronizer, subscriptionMechanism, ProcessExecutor.sequential[IO])

    (subscriptionMechanism.renewSubscription _).expects().returns(IO.unit)
  }

  private implicit lazy val eventEncoder: Encoder[ProjectSyncEvent] = Encoder.instance[ProjectSyncEvent] {
    case ProjectSyncEvent(id, slug) => json"""{
      "categoryName": "PROJECT_SYNC",
      "project": {
        "id":   $id,
        "slug": $slug
      }
    }"""
  }

  private lazy val projectSyncEvents = for {
    id   <- projectIds
    slug <- projectSlugs
  } yield ProjectSyncEvent(id, slug)
}
