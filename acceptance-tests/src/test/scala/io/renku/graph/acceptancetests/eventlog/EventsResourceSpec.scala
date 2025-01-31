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

package io.renku.graph.acceptancetests
package eventlog

import cats.syntax.all._
import data.Project.Statistics.CommitsCount
import data._
import flows.TSProvisioning
import io.circe.{Decoder, Json}
import io.renku.generators.CommonGraphGenerators.authUsers
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.EventsGenerators.commitIds
import io.renku.graph.model.events.{EventId, EventProcessingTime, EventStatus}
import io.renku.graph.model.projects.Role
import io.renku.graph.model.testentities.cliShapedPersons
import io.renku.graph.model.testentities.generators.EntitiesGenerators.{anyVisibility, removeMembers, renkuProjectEntities}
import io.renku.http.client.UrlEncoder.urlEncode
import org.http4s.Status._
import tooling.{AcceptanceSpec, ApplicationServices}

class EventsResourceSpec extends AcceptanceSpec with ApplicationServices with TSProvisioning {

  Feature("GET /events?project-slug=<slug> to return info about all the project events") {

    Scenario("As a user I would like to see all events from the project with the given slug") {

      val commits = commitIds.generateNonEmptyList(max = 6)
      val user    = authUsers.generateOne
      val project = dataProjects(
        renkuProjectEntities(anyVisibility, creatorGen = cliShapedPersons).modify(removeMembers()),
        CommitsCount(commits.size)
      ).map(replaceCreatorFrom(cliShapedPersons.generateOne, user.id))
        .map(addMemberWithId(user.id, Role.Owner))
        .generateOne

      Given("there are no events for the given project in EL")
      val noEventsResponse = eventLogClient.GET(s"events?project-slug=${urlEncode(project.slug.show)}")
      noEventsResponse.status                  shouldBe Ok
      noEventsResponse.jsonBody.as[List[Json]] shouldBe Nil.asRight

      And("the resource returns OK with an empty array")
      gitLabStub.addAuthenticated(user)
      gitLabStub.setupProject(project, commits.toList: _*)
      mockCommitDataOnTripleGenerator(project, toPayloadJsonLD(project), commits)

      When("new events are added to the store")
      `data in the Triples Store`(project, commits, user.accessToken)

      eventually {
        Then("the user can see the events on the endpoint")
        val eventsResponse = eventLogClient.GET(s"events?project-slug=${urlEncode(project.slug.show)}")
        eventsResponse.status shouldBe Ok
        val Right(events) = eventsResponse.jsonBody.as[List[EventInfo]]
        events.size               shouldBe commits.size
        events.map(_.eventId.value) should contain theSameElementsAs commits.toList.map(_.value)
      }
    }
  }

  private case class EventInfo(eventId:         EventId,
                               status:          EventStatus,
                               maybeMessage:    Option[String],
                               processingTimes: List[StatusProcessingTime]
  )
  private case class StatusProcessingTime(status: EventStatus, processingTime: EventProcessingTime)

  private implicit lazy val infoDecoder: Decoder[EventInfo] = cursor => {
    import io.renku.tinytypes.json.TinyTypeDecoders._

    implicit val statusProcessingTimeDecoder: Decoder[StatusProcessingTime] = cursor =>
      for {
        status         <- cursor.downField("status").as[EventStatus]
        processingTime <- cursor.downField("processingTime").as[EventProcessingTime]
      } yield StatusProcessingTime(status, processingTime)

    for {
      id              <- cursor.downField("id").as[EventId]
      status          <- cursor.downField("status").as[EventStatus]
      maybeMessage    <- cursor.downField("message").as[Option[String]]
      processingTimes <- cursor.downField("processingTimes").as[List[StatusProcessingTime]]
    } yield EventInfo(id, status, maybeMessage, processingTimes)
  }
}
