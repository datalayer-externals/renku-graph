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

package io.renku.triplesgenerator.tsprovisioning.transformation.namedgraphs.plans

import eu.timepit.refined.auto._
import io.renku.graph.model.Schemas.{prov, schema}
import io.renku.graph.model.entities.StepPlan
import io.renku.graph.model.{GraphClass, plans, projects}
import io.renku.jsonld.syntax._
import io.renku.triplesstore.SparqlQuery
import io.renku.triplesstore.SparqlQuery.Prefixes
import io.renku.triplesstore.client.syntax._

private object UpdatesCreator extends UpdatesCreator

private trait UpdatesCreator {

  def queriesDeletingDate(projectId:      projects.ResourceId,
                          stepPlan:       StepPlan,
                          tsCreatedDates: List[plans.DateCreated]
  ): List[SparqlQuery] =
    Option
      .when(tsCreatedDates.size > 1 || !tsCreatedDates.forall(_ == stepPlan.dateCreated)) {
        SparqlQuery.of(
          name = "transformation - delete activity author link",
          Prefixes of (schema -> "schema", prov -> "prov"),
          sparql"""|DELETE { GRAPH ${GraphClass.Project.id(projectId)} { ?planId schema:dateCreated ?dateCreated } }
                   |WHERE {
                   |  BIND (${stepPlan.resourceId.asEntityId} AS ?planId)
                   |  GRAPH ${GraphClass.Project.id(projectId)} {
                   |    ?planId a prov:Plan;
                   |            schema:dateCreated ?dateCreated
                   |  }
                   |}
                   |""".stripMargin
        )
      }
      .toList
}
