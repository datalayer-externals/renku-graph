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

import cats.syntax.all._
import data.Project.Statistics.CommitsCount
import data._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import flows.{AccessTokenPresence, TSProvisioning}
import io.circe.Json
import io.renku.generators.CommonGraphGenerators.authUsers
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.EventsGenerators.commitIds
import io.renku.graph.model.events.EventStatusProgress
import io.renku.graph.model.testentities.generators.EntitiesGenerators._
import org.http4s.Status._
import org.scalatest.concurrent.Eventually
import testing.AcceptanceTestPatience
import tooling.{AcceptanceSpec, ApplicationServices, ModelImplicits}

class EventsProcessingStatusSpec
    extends AcceptanceSpec
    with ModelImplicits
    with ApplicationServices
    with TSProvisioning
    with AccessTokenPresence
    with Eventually
    with AcceptanceTestPatience {

  private val numberOfEvents: Int Refined Positive = 5

  Feature("Status of processing for a given project") {

    Scenario("As a user I would like to see processing status of my project") {

      val user = authUsers.generateOne
      val project =
        dataProjects(renkuProjectEntities(visibilityPublic, creatorGen = cliShapedPersons).modify(removeMembers()),
                     CommitsCount(numberOfEvents.value)
        ).map(addMemberWithId(user.id)).generateOne

      When("there's no webhook for a given project in GitLab")
      Then("the status endpoint should return NOT_FOUND")
      webhookServiceClient.fetchProcessingStatus(project.id).status shouldBe NotFound

      When("there is a webhook created")
      And("there are events under processing")
      val allCommitIds = commitIds.generateNonEmptyList(min = numberOfEvents, max = numberOfEvents)
      gitLabStub.addAuthenticated(user)
      gitLabStub.setupProject(project, allCommitIds.toList: _*)
      mockCommitDataOnTripleGenerator(project, toPayloadJsonLD(project), allCommitIds)
      `data in the Triples Store`(project, allCommitIds, user.accessToken)

      Then("the status endpoint should return OK with some progress info")
      eventually {
        val response = webhookServiceClient.fetchProcessingStatus(project.id)

        response.status shouldBe Ok

        val responseJson = response.jsonBody.hcursor
        responseJson.downField("activated").as[Boolean] shouldBe true.asRight

        val progressObjCursor = responseJson.downField("progress").as[Json].fold(throw _, identity).hcursor
        progressObjCursor.downField("done").as[Int]         shouldBe EventStatusProgress.Stage.Final.value.asRight
        progressObjCursor.downField("total").as[Int]        shouldBe EventStatusProgress.Stage.Final.value.asRight
        progressObjCursor.downField("percentage").as[Float] shouldBe 100f.asRight

        val detailsObjCursor = responseJson.downField("details").as[Json].fold(throw _, identity).hcursor
        detailsObjCursor.downField("status").as[String]  shouldBe "success".asRight
        detailsObjCursor.downField("message").as[String] shouldBe "triples store".asRight
      }
    }
  }
}
