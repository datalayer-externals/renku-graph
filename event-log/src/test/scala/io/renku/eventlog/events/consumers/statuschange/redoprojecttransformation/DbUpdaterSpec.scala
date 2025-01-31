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

package io.renku.eventlog.events.consumers.statuschange.redoprojecttransformation

import cats.Show
import cats.data.Kleisli
import cats.syntax.all._
import io.circe.Encoder
import io.renku.eventlog.EventLogDB
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.api.events.StatusChangeEvent
import io.renku.eventlog.api.events.StatusChangeEvent.RedoProjectTransformation
import io.renku.eventlog.events.consumers.statuschange.StatusChangeEventsQueue.EventType
import io.renku.eventlog.events.consumers.statuschange.{DBUpdateResults, DBUpdater, StatusChangeEventsQueue}
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import skunk.Session

import scala.util.Try

class DbUpdaterSpec extends AnyWordSpec with should.Matchers with MockFactory {

  "updateDB" should {

    "offer the RedoProjectTransformation event to the queue" in new TestCase {
      (queue
        .offer(_: RedoProjectTransformation)(_: Encoder[RedoProjectTransformation],
                                             _: EventType[RedoProjectTransformation],
                                             _: Show[RedoProjectTransformation]
        ))
        .expects(event, *, *, *)
        .returning(Kleisli.pure(()))

      handler.updateDB(event)(session) shouldBe DBUpdateResults.ForProjects.empty.pure[Try]
    }
  }

  "onRollback" should {
    "return RollbackOp.empty" in new TestCase {
      implicit val sr: SessionResource[Try] = mock[io.renku.db.SessionResource[Try, EventLogDB]]
      handler.onRollback(event) shouldBe DBUpdater.RollbackOp.empty[Try]
    }
  }

  private trait TestCase {
    val event = GraphModelGenerators.projectSlugs
      .map(StatusChangeEvent.ProjectSlug.apply)
      .map(RedoProjectTransformation(_))
      .generateOne
    val session = mock[Session[Try]]

    val queue   = mock[StatusChangeEventsQueue[Try]]
    val handler = new DbUpdater[Try](queue)
  }
}
