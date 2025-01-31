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

package io.renku.graph.model.entities

import cats.syntax.all._
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators
import io.renku.graph.model.associations.ResourceId
import io.renku.graph.model.testentities.generators.EntitiesGenerators
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class AssociationLensSpec extends AnyWordSpec with should.Matchers with EntitiesGenerators {

  "associationAgent" should {
    "get and set" in {
      val assoc1 = createAssociationAgent
      val assoc2 = createAssociationPerson

      AssociationLens.associationAgent.get(assoc1) shouldBe Left(assoc1.agent)
      AssociationLens.associationAgent.get(assoc2) shouldBe Right(assoc2.agent)

      AssociationLens.associationAgent.replace(assoc1.agent.asLeft)(assoc2) shouldBe
        Association.WithRenkuAgent(assoc2.resourceId, assoc1.agent, assoc2.planId)
      AssociationLens.associationAgent.replace(assoc2.agent.asRight)(assoc1) shouldBe
        Association.WithPersonAgent(assoc1.resourceId, assoc2.agent, assoc1.planId)

      val assoc3 = createAssociationAgent
      val assoc4 = createAssociationPerson
      AssociationLens.associationAgent.replace(assoc1.agent.asLeft)(assoc3) shouldBe
        assoc3.copy(agent = assoc1.agent)
      AssociationLens.associationAgent.replace(assoc2.agent.asRight)(assoc4) shouldBe
        assoc4.copy(agent = assoc2.agent)
    }
  }

  private def createPlan =
    stepPlanEntities()
      .apply(GraphModelGenerators.projectCreatedDates().generateOne)
      .generateOne
      .to[StepPlan]

  private def createAssociationAgent: Association.WithRenkuAgent =
    Association.WithRenkuAgent(
      ResourceId(s"http://localhost/${activityIds.generateOne.value}"),
      EntitiesGenerators.agentEntities.generateOne,
      createPlan.resourceId
    )

  private def createAssociationPerson: Association.WithPersonAgent =
    Association.WithPersonAgent(
      ResourceId(s"http://localhost/${activityIds.generateOne.value}"),
      personEntities.generateOne.to[Person],
      createPlan.resourceId
    )
}
