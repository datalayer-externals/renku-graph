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

package io.renku.triplesgenerator.tsprovisioning

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.syntax.all._
import io.renku.graph.model
import io.renku.graph.model.entities.Dataset.Provenance
import io.renku.graph.model.entities.Dataset.Provenance.{ImportedInternal, Modified}
import io.renku.graph.model.entities._
import monocle.{Lens, Traversal}

import scala.annotation.tailrec

private trait ProjectFunctions {

  import ProjectFunctions.Lenses._
  import ProjectFunctions._

  def update(oldPerson: Person, newPerson: Person): Project => Project = project =>
    project
      .updateMember(oldPerson, _.copy(person = newPerson))
      .updateCreator(oldPerson, newPerson)
      .updateActivities(updateAuthorsAndAgents(oldPerson, newPerson))
      .updateDatasets(updateDatasetCreators(oldPerson, newPerson))
      .updatePlans(updatePlanCreators(oldPerson, newPerson))

  def update(oldDataset: Dataset[Provenance], newDataset: Dataset[Provenance]): Project => Project = project =>
    if (oldDataset == newDataset) project
    else
      project.updateDatasets(
        datasetsLens modify {
          case `oldDataset` => newDataset
          case dataset      => dataset
        }
      )

  lazy val findInternallyImportedDatasets: Project => List[Dataset[Provenance.ImportedInternal]] = project => {
    val invalidatedDatasets = findInvalidatedDatasets(project)

    project.datasets.flatMap { dataset =>
      dataset.provenance match {
        case _: ImportedInternal =>
          Option.unless(
            invalidatedDatasets.exists(_.identification.resourceId == dataset.identification.resourceId)
          )(dataset.asInstanceOf[Dataset[Provenance.ImportedInternal]])
        case _ => Option.empty[Dataset[Dataset.Provenance.ImportedInternal]]
      }
    }
  }

  lazy val findModifiedDatasets: Project => List[Dataset[Provenance.Modified]] = project => {
    val invalidatedDatasets = findInvalidatedDatasets(project)

    project.datasets flatMap { dataset =>
      dataset.provenance match {
        case p: Modified if p.maybeInvalidationTime.isEmpty =>
          Option.unless(
            invalidatedDatasets.exists(_.identification.resourceId == dataset.identification.resourceId)
          )(dataset.asInstanceOf[Dataset[Provenance.Modified]])
        case _ => Option.empty[Dataset[Dataset.Provenance.Modified]]
      }
    }
  }

  lazy val findInvalidatedDatasets: Project => List[Dataset[Provenance]] = project =>
    project.datasets.foldLeft(List.empty[Dataset[Provenance]]) { (invalidateDatasets, dataset) =>
      dataset.provenance match {
        case p: Modified if p.maybeInvalidationTime.isDefined =>
          project.datasets
            .find(_.resourceId.value == p.derivedFrom.value)
            .map(_ :: invalidateDatasets)
            .getOrElse(invalidateDatasets)
        case _ => invalidateDatasets
      }
    }

  def findTopmostDerivedFrom[F[_]: MonadThrow](dataset: Dataset[Provenance],
                                               project: Project
  ): F[model.datasets.TopmostDerivedFrom] = {
    import model.datasets.TopmostDerivedFrom

    @tailrec
    def findParent(topmostDerivedFrom: TopmostDerivedFrom): F[TopmostDerivedFrom] =
      project.datasets.find(_.identification.resourceId.show == topmostDerivedFrom.show) match {
        case None =>
          new IllegalStateException(show"Broken derivation hierarchy for $topmostDerivedFrom")
            .raiseError[F, TopmostDerivedFrom]
        case Some(ds) =>
          ds.provenance match {
            case prov: Provenance.Modified => findParent(prov.topmostDerivedFrom)
            case prov => prov.topmostDerivedFrom.pure[F]
          }
      }

    findParent(dataset.provenance.topmostDerivedFrom)
  }
}

private object ProjectFunctions extends ProjectFunctions {

  import Lenses._

  private implicit class ProjectOps(project: Project) {

    def updateMember(oldMember: Project.Member, newMember: Project.Member): Project =
      projectMembersLens
        .andThen(membersLens)
        .modify {
          case `oldMember` => newMember
          case p           => p
        }(project)

    def updateMember(person: Person, f: Project.Member => Project.Member): Project =
      projectMembersLens
        .andThen(membersLens)
        .modify { member =>
          if (member.person == person) f(member)
          else member
        }(project)

    def updateCreator(oldPerson: Person, newPerson: Person): Project =
      projectCreatorLens.modify {
        case Some(`oldPerson`) => Some(newPerson)
        case other             => other
      }(project)

    def updateActivities(function: List[Activity] => List[Activity]): Project =
      projectActivitiesLens.modify(function)(project)

    def updateDatasets(function: List[Dataset[Dataset.Provenance]] => List[Dataset[Dataset.Provenance]]): Project =
      projectDatasetsLens.modify(function)(project)

    def updatePlans(function: List[Plan] => List[Plan]): Project =
      projectPlans.modify(function)(project)
  }

  private def updateAuthorsAndAgents(oldPerson: Person, newPerson: Person): List[Activity] => List[Activity] = {

    def updateAuthor(oldPerson: Person, newPerson: Person) =
      ActivityLens.activityAuthor.modify {
        case `oldPerson` => newPerson
        case other       => other
      }

    def updateAssociationAgents(oldPerson: Person, newPerson: Person): Activity => Activity =
      ActivityLens.activityAssociationAgent.modify {
        case Right(`oldPerson`) => Right(newPerson)
        case other              => other
      }

    activitiesLens.modify {
      updateAuthor(oldPerson, newPerson) >>> updateAssociationAgents(oldPerson, newPerson)
    }
  }

  private def updateDatasetCreators(oldPerson: Person,
                                    newPerson: Person
  ): List[Dataset[Provenance]] => List[Dataset[Provenance]] =
    datasetsLens
      .andThen(provenanceLens >>> provCreatorsLens)
      .andThen(creatorsLens)
      .modify {
        case `oldPerson` => newPerson
        case p           => p
      }

  private def updatePlanCreators(oldPerson: Person, newPerson: Person): List[Plan] => List[Plan] =
    plansLens
      .andThen(PlanLens.planCreators)
      .andThen(Traversal.fromTraverse[List, Person])
      .modify {
        case `oldPerson` => newPerson
        case p           => p
      }

  private object Lenses {
    val membersLens = Traversal.fromTraverse[List, Project.Member]
    val projectMembersLens = Lens[Project, List[Project.Member]](_.members.toList)(persons => {
      case p: RenkuProject.WithoutParent    => p.copy(members = persons.toSet)
      case p: RenkuProject.WithParent       => p.copy(members = persons.toSet)
      case p: NonRenkuProject.WithoutParent => p.copy(members = persons.toSet)
      case p: NonRenkuProject.WithParent    => p.copy(members = persons.toSet)
    })
    val projectCreatorLens = Lens[Project, Option[Person]](_.maybeCreator)(maybeCreator => {
      case p: RenkuProject.WithoutParent    => p.copy(maybeCreator = maybeCreator)
      case p: RenkuProject.WithParent       => p.copy(maybeCreator = maybeCreator)
      case p: NonRenkuProject.WithoutParent => p.copy(maybeCreator = maybeCreator)
      case p: NonRenkuProject.WithParent    => p.copy(maybeCreator = maybeCreator)
    })

    val projectActivitiesLens = Lens[Project, List[Activity]](_.activities)(activities => {
      case p: RenkuProject.WithoutParent => p.copy(activities = activities)
      case p: RenkuProject.WithParent    => p.copy(activities = activities)
      case p: NonRenkuProject            => p
    })
    val activitiesLens: Traversal[List[Activity], Activity] = Traversal.fromTraverse[List, Activity]

    val projectDatasetsLens = Lens[Project, List[Dataset[Dataset.Provenance]]](_.datasets)(datasets => {
      case p: RenkuProject.WithoutParent => p.copy(datasets = datasets)
      case p: RenkuProject.WithParent    => p.copy(datasets = datasets)
      case p: NonRenkuProject            => p
    })
    val datasetsLens   = Traversal.fromTraverse[List, Dataset[Provenance]]
    val provenanceLens = Lens[Dataset[Provenance], Provenance](_.provenance)(p => d => d.copy(provenance = p))
    val creatorsLens   = Traversal.fromTraverse[NonEmptyList, Person]
    val provCreatorsLens = Lens[Provenance, NonEmptyList[Person]](_.creators) { crts =>
      {
        case p: Provenance.Internal                         => p.copy(creators = crts.sortBy(_.name))
        case p: Provenance.ImportedExternal                 => p.copy(creators = crts.sortBy(_.name))
        case p: Provenance.ImportedInternalAncestorExternal => p.copy(creators = crts.sortBy(_.name))
        case p: Provenance.ImportedInternalAncestorInternal => p.copy(creators = crts.sortBy(_.name))
        case p: Provenance.Modified                         => p.copy(creators = crts.sortBy(_.name))
      }
    }

    val projectPlans: Lens[Project, List[Plan]] = Lens[Project, List[Plan]](_.plans)(plans => {
      case p: RenkuProject.WithoutParent => p.copy(plans = plans)
      case p: RenkuProject.WithParent    => p.copy(plans = plans)
      case p: NonRenkuProject            => p
    })
    val plansLens: Traversal[List[Plan], Plan] = Traversal.fromTraverse[List, Plan]
  }
}
