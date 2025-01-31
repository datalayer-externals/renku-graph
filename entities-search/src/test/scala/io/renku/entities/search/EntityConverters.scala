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

package io.renku.entities.search

import io.renku.entities.search.model.Entity.Workflow.WorkflowType
import io.renku.entities.search.model._
import io.renku.graph.model.testentities.{Entity => _, _}
import io.renku.graph.model.{RenkuUrl, datasets, testentities}

private object EntityConverters {

  private[search] implicit def projectConverter[P <: testentities.Project]: P => Entity.Project = project =>
    Entity.Project(
      MatchingScore.min,
      project.slug,
      project.name,
      project.visibility,
      project.dateCreated,
      project.dateModified,
      project.maybeCreator.map(_.name),
      project.keywords.toList.sorted,
      project.maybeDescription,
      project.images
    )

  private[search] implicit def datasetConverter[P <: testentities.Project](implicit
      renkuUrl: RenkuUrl
  ): ((testentities.Dataset[testentities.Dataset.Provenance], P)) => Entity.Dataset = { case (dataset, project) =>
    Entity.Dataset(
      MatchingScore.min,
      dataset.provenance.topmostSameAs,
      dataset.identification.name,
      project.visibility,
      dataset.provenance.date,
      dataset.provenance match {
        case m: Dataset.Provenance.Modified =>
          Some(datasets.DateModified(m.date.value))
        case _ => None
      },
      dataset.provenance.creators.map(_.name).toList.sorted,
      dataset.additionalInfo.keywords.sorted,
      dataset.additionalInfo.maybeDescription,
      dataset.additionalInfo.images,
      exemplarProjectSlug = project.slug
    )
  }

  private[search] implicit class ProjectDatasetOps[PROV <: testentities.Dataset.Provenance, +P <: testentities.Project](
      datasetAndProject: (testentities.Dataset[PROV], P)
  )(implicit renkuUrl: RenkuUrl) {
    def to[T](implicit convert: ((testentities.Dataset[PROV], P)) => T): T = convert(datasetAndProject)
  }

  private[search] implicit def planConverter[P <: testentities.Project]: ((testentities.Plan, P)) => Entity.Workflow = {
    case (plan, project) =>
      Entity.Workflow(
        MatchingScore.min,
        plan.name,
        project.visibility,
        plan.dateCreated,
        plan.keywords.sorted,
        plan.maybeDescription,
        plan match {
          case _: CompositePlan => WorkflowType.Composite
          case _: StepPlan      => WorkflowType.Step
        }
      )
  }

  private[search] implicit class ProjectPlanOps[+P <: testentities.Project](planAndProject: (testentities.Plan, P)) {
    def to[T](implicit convert: ((testentities.Plan, P)) => T): T = convert(planAndProject)
  }

  private[search] implicit def personConverter[P <: testentities.Person]: P => Entity.Person = person =>
    Entity.Person(MatchingScore.min, person.name)
}
