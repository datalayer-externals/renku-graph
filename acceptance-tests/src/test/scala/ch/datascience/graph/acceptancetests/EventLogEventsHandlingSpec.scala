/*
 * Copyright 2020 Swiss Data Science Center (SDSC)
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

package ch.datascience.graph.acceptancetests
import ch.datascience.generators.CommonGraphGenerators.accessTokens
import ch.datascience.generators.Generators.Implicits._
import ch.datascience.graph.acceptancetests.db.EventLog
import ch.datascience.graph.acceptancetests.flows.AccessTokenPresence.givenAccessTokenPresentFor
import ch.datascience.graph.acceptancetests.stubs.GitLab._
import ch.datascience.graph.acceptancetests.stubs.RemoteTriplesGenerator._
import ch.datascience.graph.acceptancetests.testing.AcceptanceTestPatience
import ch.datascience.graph.acceptancetests.tooling.{GraphServices, RDFStore}
import ch.datascience.graph.model.EventsGenerators.commitIds
import ch.datascience.graph.model.events.EventStatus._
import ch.datascience.http.client.AccessToken
import ch.datascience.knowledgegraph.projects.ProjectsGenerators._
import ch.datascience.webhookservice.model.HookToken
import org.http4s.Status._
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

class EventLogEventsHandlingSpec
    extends AnyFeatureSpec
    with GivenWhenThen
    with GraphServices
    with Eventually
    with AcceptanceTestPatience
    with should.Matchers {

  Feature("Commit Events from the Event Log get translated to triples in the RDF Store") {

    Scenario("Not processed Commit Events in the Event Log should be picked-up for processing") {

      implicit val accessToken: AccessToken = accessTokens.generateOne
      val project   = projects.generateOne
      val projectId = project.id
      val commitId  = commitIds.generateOne

      Given("commit with the commit id matching Push Event's 'after' exists on the project in GitLab")
      `GET <gitlab>/api/v4/projects/:id/repository/commits/:sha returning OK with some event`(projectId, commitId)

      And("RDF triples are generated by the Remote Triples Generator")
      `GET <triples-generator>/projects/:id/commits/:id returning OK with some triples`(project, commitId)

      And("access token is present")
      givenAccessTokenPresentFor(project)

      And("project exists in GitLab")
      `GET <gitlab>/api/v4/projects/:path returning OK with`(project)

      When("a Push Event arrives")
      webhookServiceClient
        .POST("webhooks/events", HookToken(projectId), data.GitLab.pushEvent(project, commitId))
        .status shouldBe Accepted

      Then("there should be an Commit Event added to the Event Log")
      eventually {
        EventLog.findEvents(projectId, status = New) shouldBe List(commitId)
      }

      When("the Event is picked up by the Triples Generator")
      Then("RDF triples got generated and pushed to the RDF Store")
      eventually {
        RDFStore.findAllTriplesNumber() should be > 0
      }

      And(s"the relevant Event got marked as $TriplesStore in the Log")
      eventually {
        EventLog.findEvents(projectId, status = TriplesStore) should contain(commitId)
      }
    }
  }
}
