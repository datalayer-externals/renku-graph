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

import cats.syntax.all._
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model._
import GraphModelGenerators.datasetTopmostDerivedFroms
import cats.data.NonEmptyList
import io.renku.graph.model.datasets.TopmostDerivedFrom
import io.renku.graph.model.entities.{ActivityLens, PlanLens}
import io.renku.graph.model.testentities._
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.{Failure, Try}

class ProjectFunctionsSpec extends AnyWordSpec with should.Matchers with ScalaCheckPropertyChecks {

  import ProjectFunctions._

  "update - person" should {
    val oldMember = projectMemberEntities().generateOne
    val oldPerson = oldMember.person

    "replace the old person with the new on project" in {
      Set(
        renkuProjectEntities(anyVisibility)
          .modify(
            _.copy(maybeCreator = Some(oldPerson), members = Set(oldMember, projectMemberEntities().generateOne))
          ),
        renkuProjectWithParentEntities(anyVisibility)
          .modify(
            _.copy(maybeCreator = Some(oldPerson), members = Set(oldMember, projectMemberEntities().generateOne))
          ),
        nonRenkuProjectEntities(anyVisibility)
          .modify(
            _.copy(maybeCreator = Some(oldPerson), members = Set(oldMember, projectMemberEntities().generateOne))
          ),
        nonRenkuProjectWithParentEntities(anyVisibility)
          .modify(
            _.copy(maybeCreator = Some(oldPerson), members = Set(oldMember, projectMemberEntities().generateOne))
          )
      ) foreach { projectGen =>
        val project           = projectGen.generateOne.to[entities.Project]
        val entitiesOldMember = oldMember.to[entities.Project.Member]

        val newPerson = personEntities().generateOne.to[entities.Person]

        val updatedProject = update(entitiesOldMember.person, newPerson)(project)

        updatedProject.members shouldBe project.members - entitiesOldMember + entitiesOldMember.copy(person = newPerson)
        updatedProject.maybeCreator shouldBe Some(newPerson)
      }
    }

    "replace the old person with the new on all activities" in {

      val project = anyRenkuProjectEntities
        .withActivities(
          activityEntities(stepPlanEntities()).map(_.copy(author = oldPerson)),
          activityEntities(stepPlanEntities())
        )
        .generateOne
        .to[entities.Project]

      val entitiesOldPerson = oldPerson.to[entities.Person]
      val newPerson         = personEntities().generateOne.to[entities.Person]

      update(entitiesOldPerson, newPerson)(project).activities shouldBe project.activities.map {
        case activity if activity.author == entitiesOldPerson => activity.copy(author = newPerson)
        case activity                                         => activity
      }
    }

    "replace the old person with the new on all activities' associations" in {

      val project = anyRenkuProjectEntities
        .withActivities(
          activityEntities(stepPlanEntities()).map(toAssociationPersonAgent(oldPerson)),
          activityEntities(stepPlanEntities())
        )
        .generateOne
        .to[entities.Project]

      val entitiesOldPerson = oldPerson.to[entities.Person]
      val newPerson         = personEntities().generateOne.to[entities.Person]

      update(entitiesOldPerson, newPerson)(project).activities shouldBe project.activities.map {
        ActivityLens.activityAssociationAgent.modify(_.as(newPerson))
      }
    }

    "replace the old person with the new person in all plan creators" in {
      val project = anyRenkuProjectEntities
        .withActivities(
          activityEntities(stepPlanEntities())
            .map(setPlanCreator(oldPerson))
            .map(toAssociationRenkuAgent(agentEntities.generateOne)),
          activityEntities(stepPlanEntities())
            .map(setPlanCreator(oldPerson))
            .map(toAssociationRenkuAgent(agentEntities.generateOne))
        )
        .generateOne
        .to[entities.Project]

      val entitiesOldPerson = oldPerson.to[entities.Person]
      val newPerson         = personEntities().generateOne.to[entities.Person]

      val updated = update(entitiesOldPerson, newPerson)(project)

      updated.plans shouldBe project.plans.map(PlanLens.planCreators.replace(List(newPerson)))
    }

    "replace the old person with the new on all datasets" in {

      val project = anyRenkuProjectEntities
        .withDatasets(
          datasetEntities(provenanceNonModified).modify(
            provenanceLens.modify(creatorsLens.modify(_ => NonEmptyList.of(oldPerson, personEntities.generateOne)))
          ),
          datasetEntities(provenanceNonModified)
        )
        .generateOne
        .to[entities.Project]

      val entitiesOldPerson = oldPerson.to[entities.Person]
      val newPerson         = personEntities().generateOne.to[entities.Person]

      val dataset1 :: dataset2 :: Nil = update(entitiesOldPerson, newPerson)(project).datasets
      dataset1.provenance.creators shouldBe project.datasets.head.provenance.creators
        .map {
          case creator if creator == entitiesOldPerson => newPerson
          case creator                                 => creator
        }
        .sortBy(_.name)
      dataset2.provenance.creators shouldBe project.datasets.tail.head.provenance.creators
    }
  }

  "update - dataset" should {

    "replace the old dataset with the new one" in {
      val (_, project) = anyRenkuProjectEntities
        .addDataset(datasetEntities(provenanceNonModified))
        .addDataset(datasetEntities(provenanceNonModified))
        .generateOne
        .map(_.to[entities.Project])

      val dataset1 :: dataset2Old :: Nil = project.datasets

      val dataset2New = datasetEntities(provenanceNonModified)(renkuUrl)(project.dateCreated).generateOne
        .to[entities.Dataset[entities.Dataset.Provenance]]

      update(dataset2Old, dataset2New)(project).datasets shouldBe List(dataset1, dataset2New)
    }
  }

  "findInternallyImportedDatasets" should {

    "return all datasets with ImportedInternalAncestorExternal or ImportedInternalAncestorInternal provenance" +
      "which are not invalidated" in {
        val (importedInternalAncestorInternal ::~ importedInternalAncestorExternal ::~ _ ::~ _ ::~ _ ::~ _, project) =
          anyRenkuProjectEntities
            .addDataset(datasetEntities(provenanceImportedInternalAncestorInternal()))
            .addDataset(datasetEntities(provenanceImportedInternalAncestorExternal))
            .addDataset(datasetEntities(provenanceInternal))
            .addDataset(datasetEntities(provenanceImportedExternal))
            .addDatasetAndModification(datasetEntities(provenanceInternal))
            .generateOne

        val originalImportedInternal =
          datasetEntities(provenanceImportedInternalAncestorInternal())(renkuUrl)(project.dateCreated).generateOne
        val projectWithAllDatasets =
          project
            .addDatasets(originalImportedInternal, originalImportedInternal.invalidateNow(personEntities))
            .to[entities.Project]

        findInternallyImportedDatasets(projectWithAllDatasets) should contain theSameElementsAs List(
          importedInternalAncestorInternal,
          importedInternalAncestorExternal
        ).map(_.to[entities.Dataset[entities.Dataset.Provenance.ImportedInternal]])
      }
  }

  "findModifiedDatasets" should {
    "return all datasets with Provenance.Modified which are not invalidations" in {
      val (_ ::~ modified ::~ _ ::~ _ ::~ _ ::~ _, project) = anyRenkuProjectEntities
        .addDatasetAndModification(datasetEntities(provenanceInternal))
        .addDataset(datasetEntities(provenanceInternal))
        .addDataset(datasetEntities(provenanceImportedExternal))
        .addDataset(datasetEntities(provenanceImportedInternalAncestorInternal()))
        .addDataset(datasetEntities(provenanceImportedInternalAncestorExternal))
        .generateOne

      val (original, modifiedBeforeInvalidation, modifiedInvalidated) =
        datasetAndModificationEntities(provenanceInternal, projectDateCreated = project.dateCreated).map {
          case (orig, modified) => (orig, modified, modified.invalidateNow(personEntities))
        }.generateOne
      val projectWithAllDatasets =
        project.addDatasets(original, modifiedBeforeInvalidation, modifiedInvalidated).to[entities.Project]

      findModifiedDatasets(projectWithAllDatasets) should contain theSameElementsAs List(
        modified.to[entities.Dataset[entities.Dataset.Provenance.Modified]]
      )
    }
  }

  "findInvalidatedDatasets" should {
    "find all invalidated datasets and not valid datasets or invalidation datasets" in {

      val (internal ::~ _ ::~ importedExternal ::~ _ ::~ ancestorInternal ::~ _ ::~ ancestorExternal ::~ _, project) =
        anyRenkuProjectEntities
          .addDatasetAndInvalidation(datasetEntities(provenanceInternal))
          .addDatasetAndInvalidation(datasetEntities(provenanceImportedExternal))
          .addDatasetAndInvalidation(datasetEntities(provenanceImportedInternalAncestorInternal()))
          .addDatasetAndInvalidation(datasetEntities(provenanceImportedInternalAncestorExternal))
          .generateOne
      val (beforeModification, modified, modificationInvalidated) =
        datasetAndModificationEntities(provenanceInternal, projectDateCreated = project.dateCreated).map {
          case internal ::~ modified => (internal, modified, modified.invalidateNow(personEntities))
        }.generateOne
      val (beforeNotInvalidatedModification, notInvalidatedModification) =
        datasetAndModificationEntities(provenanceInternal, projectDateCreated = project.dateCreated).generateOne

      findInvalidatedDatasets(
        project
          .addDatasets(beforeModification,
                       modified,
                       modificationInvalidated,
                       beforeNotInvalidatedModification,
                       notInvalidatedModification
          )
          .to[entities.Project]
      ) should contain theSameElementsAs List(
        internal,
        importedExternal,
        ancestorInternal,
        ancestorExternal,
        modified
      ).map(_.to[entities.Dataset[entities.Dataset.Provenance]])
    }
  }

  "findTopmostDerivedFrom" should {

    def topmostDerivedFromFromDerivedFrom(
        otherDs: Dataset[Dataset.Provenance.Modified]
    ): Dataset[Dataset.Provenance.Modified] => Dataset[Dataset.Provenance.Modified] =
      ds => ds.copy(provenance = ds.provenance.copy(topmostDerivedFrom = TopmostDerivedFrom(otherDs.entityId)))

    "search find the very top DS' in the derivation chain and return its topmostDerivedFrom" in {

      val (_ ::~ modification1, project) =
        anyRenkuProjectEntities.addDatasetAndModification(datasetEntities(provenanceInternal)).generateOne
      val (modification2, projectUpdate2) = project.addDataset(
        modification1.createModification().modify(topmostDerivedFromFromDerivedFrom(modification1))
      )
      val (_, projectUpdate3) = projectUpdate2.addDataset(
        modification2.createModification().modify(topmostDerivedFromFromDerivedFrom(modification2))
      )
      val finalProject = projectUpdate3.to[entities.Project]

      // at this stage topmostDerivedFrom is the same as derivedFrom
      val topDS :: mod1 :: mod2 :: mod3 :: Nil = finalProject.datasets
      mod1.provenance.topmostDerivedFrom shouldBe TopmostDerivedFrom(topDS.identification.resourceId.show)
      mod2.provenance.topmostDerivedFrom shouldBe TopmostDerivedFrom(mod1.identification.resourceId.show)
      mod3.provenance.topmostDerivedFrom shouldBe TopmostDerivedFrom(mod2.identification.resourceId.show)

      findTopmostDerivedFrom[Try](mod3, finalProject) shouldBe topDS.provenance.topmostDerivedFrom.pure[Try]
    }

    "return this DS topmostDerivedFrom for any non-modified DS" in {
      val (ds, project) = anyRenkuProjectEntities
        .addDataset(datasetEntities(provenanceNonModified))
        .generateOne
        .bimap(_.to[entities.Dataset[entities.Dataset.Provenance]], _.to[entities.Project])

      findTopmostDerivedFrom[Try](ds, project) shouldBe ds.provenance.topmostDerivedFrom.pure[Try]
    }

    "fail if the derivation chain is broken" in {
      val (_, project) =
        anyRenkuProjectEntities.addDatasetAndModification(datasetEntities(provenanceInternal)).generateOne
      val entitiesProject        = project.to[entities.Project]
      val _ :: modifiedDS :: Nil = entitiesProject.datasets

      val brokenModifiedDS = {
        val typedModifiedDS = modifiedDS.asInstanceOf[entities.Dataset[entities.Dataset.Provenance.Modified]]
        typedModifiedDS.copy(provenance =
          typedModifiedDS.provenance.copy(topmostDerivedFrom = datasetTopmostDerivedFroms.generateOne)
        )
      }

      val finalProject = update(modifiedDS, brokenModifiedDS)(entitiesProject)

      val Failure(exception) = findTopmostDerivedFrom[Try](brokenModifiedDS, finalProject)

      exception.getMessage shouldBe show"Broken derivation hierarchy for ${brokenModifiedDS.provenance.topmostDerivedFrom}"
    }
  }
}
