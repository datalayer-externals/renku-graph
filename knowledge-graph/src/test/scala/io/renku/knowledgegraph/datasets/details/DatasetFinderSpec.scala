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

package io.renku.knowledgegraph.datasets
package details

import cats.effect.IO
import cats.syntax.all._
import io.renku.entities.searchgraphs.SearchInfoDatasets
import io.renku.generators.CommonGraphGenerators.authUsers
import io.renku.generators.Generators.Implicits._
import io.renku.graph.http.server.security.Authorizer.AuthContext
import io.renku.graph.model.datasets.{Identifier, SameAs, TopmostSameAs}
import io.renku.graph.model.testentities._
import io.renku.graph.model.{RenkuUrl, projects}
import io.renku.http.server.security.model.AuthUser
import io.renku.interpreters.TestLogger
import io.renku.knowledgegraph.datasets.details.Dataset._
import io.renku.logging.TestSparqlQueryTimeRecorder
import io.renku.testtools.IOSpec
import io.renku.triplesstore.{InMemoryJenaForSpec, ProjectsDataset, SparqlQueryTimeRecorder}
import org.scalatest.OptionValues
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.Random

class DatasetFinderSpec
    extends AnyWordSpec
    with should.Matchers
    with OptionValues
    with InMemoryJenaForSpec
    with ProjectsDataset
    with SearchInfoDatasets
    with ScalaCheckPropertyChecks
    with IOSpec {

  "findDataset" should {

    "return details of the dataset with the given id " +
      "- a case of a non-modified renku dataset used in a single project" in new TestCase {

        forAll(
          anyRenkuProjectEntities(visibilityPublic) addDataset datasetEntities(provenanceInternal),
          anyRenkuProjectEntities(visibilityPublic) addDataset datasetEntities(provenanceNonModified)
        ) { case ((dataset, project), (_, otherProject)) =>
          provisionTestProjects(project, otherProject).unsafeRunSync()

          val expected = internalToNonModified(dataset, project)

          findById(dataset.identifier, project.slug).value                          shouldBe expected
          findByTopmostSameAs(dataset.provenance.topmostSameAs, project.slug).value shouldBe expected

          clear(projectsDataset)
        }
      }

    "return details of the dataset with the given id " +
      "- a case when unrelated projects are using the same External dataset" in new TestCase {

        val commonSameAs = datasetExternalSameAs.generateOne
        val (dataset1, project1) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .generateOne
        val (dataset2, project2) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .generateOne

        provisionTestProjects(
          project1,
          project2,
          anyRenkuProjectEntities(visibilityPublic).withDatasets(datasetEntities(provenanceNonModified)).generateOne
        ).unsafeRunSync()

        val expectedDS1 = importedExternalToNonModified(dataset1, project1)
          .copy(usedIn = List(toDatasetProject(project1, dataset1), toDatasetProject(project2, dataset2)).sorted)
        findById(dataset1.identifier, project1.slug).value shouldBe expectedDS1

        val expectedDS2 = importedExternalToNonModified(dataset2, project2)
          .copy(usedIn = List(toDatasetProject(project1, dataset1), toDatasetProject(project2, dataset2)).sorted)
        findById(dataset2.identifier, project2.slug).value shouldBe expectedDS2

        findBySameAs(commonSameAs, project1.slug, project2.slug).value should (be(expectedDS1) or be(expectedDS2))
      }

    "return details of the dataset with the given id " +
      "- a case where the dataset is modified" in new TestCase {

        val commonSameAs = datasetExternalSameAs.generateOne
        val (originalDs -> dataset1Modified, project1) = anyRenkuProjectEntities(visibilityPublic)
          .addDatasetAndModification(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .generateOne
        val (dataset2, project2) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .generateOne

        provisionTestProjects(project1, project2).unsafeRunSync()

        val expectedDS2 = importedExternalToNonModified(dataset2, project2)
        findById(dataset2.identifier, project2.slug).value shouldBe expectedDS2
        findBySameAs(commonSameAs, project2.slug).value    shouldBe expectedDS2

        val expectedDS1Modified = modifiedToModified(dataset1Modified, originalDs.provenance.date, project1)
        findById(dataset1Modified.identifier, project1.slug).value                          shouldBe expectedDS1Modified
        findByTopmostSameAs(dataset1Modified.provenance.topmostSameAs, project1.slug).value shouldBe expectedDS1Modified
      }

    "return details of the dataset with the given id " +
      "- a case where unrelated projects are using the same Internal dataset" in new TestCase {

        val sourceDataset -> sourceProject =
          anyRenkuProjectEntities(visibilityPublic).addDataset(datasetEntities(provenanceInternal)).generateOne
        val (dataset1, project1) = renkuProjectEntities(visibilityPublic).importDataset(sourceDataset).generateOne
        val (dataset2, project2) = renkuProjectEntities(visibilityPublic).importDataset(sourceDataset).generateOne

        provisionTestProjects(sourceProject, project1, project2).unsafeRunSync()

        val expectedSourceDS = internalToNonModified(sourceDataset, sourceProject)
          .copy(usedIn =
            List(toDatasetProject(sourceProject, sourceDataset),
                 toDatasetProject(project1, dataset1),
                 toDatasetProject(project2, dataset2)
            ).sorted
          )
        findById(sourceDataset.identifier, sourceProject.slug).value shouldBe expectedSourceDS

        val expectedDS2 = importedInternalToNonModified(dataset2, project2)
          .copy(usedIn = expectedSourceDS.usedIn)
        findById(dataset2.identifier, project2.slug).value shouldBe expectedDS2

        val expectedDS1 = importedInternalToNonModified(dataset1, project1)
          .copy(usedIn = expectedSourceDS.usedIn)
        findBySameAs(SameAs(sourceDataset.entityId), sourceProject.slug, project1.slug, project2.slug).value should
          (be(expectedSourceDS) or be(expectedDS2) or be(expectedDS1))
      }

    "return None if there are no datasets with the given id" in new TestCase {
      findById(datasetIdentifiers.generateOne) shouldBe None
      findBySameAs(datasetSameAs.generateOne)  shouldBe None
    }

    "return None if dataset was invalidated" in new TestCase {

      val (original -> invalidation, project) =
        renkuProjectEntities(visibilityPublic)
          .addDatasetAndInvalidation(datasetEntities(provenanceInternal))
          .generateOne

      provisionTestProject(project).unsafeRunSync()

      findById(original.identifier, project.slug)                              shouldBe None
      findByTopmostSameAs(original.provenance.topmostSameAs, project.slug)     shouldBe None
      findById(invalidation.identifier, project.slug)                          shouldBe None
      findByTopmostSameAs(invalidation.provenance.topmostSameAs, project.slug) shouldBe None
    }

    "return a dataset without invalidated part" in new TestCase {

      val dataset -> project = anyRenkuProjectEntities(visibilityPublic)
        .addDataset(
          datasetEntities(provenanceInternal)
            .modify { ds =>
              ds.copy(parts = datasetPartEntities(ds.provenance.date.instant).generateNonEmptyList().toList)
            }
        )
        .generateOne

      val partToInvalidate           = Random.shuffle(dataset.parts).head
      val datasetWithInvalidatedPart = dataset.invalidatePartNow(partToInvalidate, personEntities)
      val projectBothDatasets        = project.addDatasets(datasetWithInvalidatedPart)

      provisionTestProject(projectBothDatasets).unsafeRunSync()

      val expectedDS = internalToNonModified(dataset, project).copy(usedIn = Nil)
      findById(dataset.identifier, project.slug).value                    shouldBe expectedDS
      findByTopmostSameAs(dataset.provenance.topmostSameAs, project.slug) shouldBe None

      val expectedDSWithInvalidatedPart =
        modifiedToModified(datasetWithInvalidatedPart, dataset.provenance.date, projectBothDatasets)
      findById(datasetWithInvalidatedPart.identifier, project.slug).value shouldBe expectedDSWithInvalidatedPart
      findByTopmostSameAs(datasetWithInvalidatedPart.provenance.topmostSameAs,
                          project.slug
      ).value shouldBe expectedDSWithInvalidatedPart
    }

    "not return a dataset if the user is not authorised for the project where the DS belongs to" in new TestCase {

      val (dataset, project) =
        anyRenkuProjectEntities(visibilityPublic).addDataset(datasetEntities(provenanceInternal)).generateOne

      provisionTestProject(project).unsafeRunSync()

      findById(dataset.identifier, projectSlugs.generateOne)                          shouldBe None
      findByTopmostSameAs(dataset.provenance.topmostSameAs, projectSlugs.generateOne) shouldBe None
    }

    "return dataset without usedIn to which the user has no access" in new TestCase {

      val authUser = authUsers.generateOne
      val (dataset, project) = renkuProjectEntities(visibilityNonPublic)
        .map(replaceMembers(Set(projectMemberEntities(authUser.id.some).generateOne)))
        .addDataset(datasetEntities(provenanceInternal))
        .generateOne
      val (_, otherProject) = renkuProjectEntities(visibilityPrivate)
        .map(replaceMembers(Set(projectMemberEntities(withGitLabId).generateOne)))
        .importDataset(dataset)
        .generateOne

      provisionTestProjects(project, otherProject).unsafeRunSync()

      val expectedDS = internalToNonModified(dataset, project)
      val byId       = findById(dataset.identifier, authUser, project.slug).value
      val byTopmost  = findByTopmostSameAs(dataset.provenance.topmostSameAs, authUser, project.slug).value
      byId      shouldBe expectedDS
      byTopmost shouldBe expectedDS
    }

    "return dataset with usedIn to which the user has access" in new TestCase {

      val authUser = authUsers.generateOne
      val (dataset, project) = renkuProjectEntities(visibilityNonPublic)
        .map(replaceMembers(Set(projectMemberEntities(authUser.id.some).generateOne)))
        .addDataset(datasetEntities(provenanceInternal))
        .generateOne
      val (otherDS, otherProject) = renkuProjectEntities(visibilityNonPublic)
        .map(replaceMembers(projectMemberEntities(withGitLabId).generateSet() ++ project.members))
        .importDataset(dataset)
        .generateOne

      provisionTestProjects(project, otherProject).unsafeRunSync()

      val expectedUsedIns = List(toDatasetProject(project, dataset), toDatasetProject(otherProject, otherDS)).sorted
      val expectedDS      = internalToNonModified(dataset, project).copy(usedIn = expectedUsedIns)
      val expectedOtherDS = importedInternalToNonModified(otherDS, otherProject).copy(usedIn = expectedUsedIns)
      findById(dataset.identifier, authUser, project.slug, otherProject.slug).value shouldBe expectedDS
      findByTopmostSameAs(dataset.provenance.topmostSameAs, authUser, project.slug, otherProject.slug).value should
        (be(expectedDS) or be(expectedOtherDS))
    }
  }

  "findDataset in case of forks" should {

    "return details of the dataset with the given id " +
      "- a case where an Internal dataset is defined on a project which has a fork" in new TestCase {

        val (originalDataset, originalProject -> fork) = renkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceInternal))
          .forkOnce()
          .generateOne

        provisionTestProjects(originalProject, fork).unsafeRunSync()

        assume(originalProject.datasets === fork.datasets,
               "Datasets on original project and its fork should be the same"
        )

        val expectedDS = internalToNonModified(originalDataset, originalProject)
          .copy(usedIn =
            List(toDatasetProject(originalProject, originalDataset), toDatasetProject(fork, originalDataset)).sorted
          )
        findById(originalDataset.identifier, originalProject.slug).value                          shouldBe expectedDS
        findByTopmostSameAs(originalDataset.provenance.topmostSameAs, originalProject.slug).value shouldBe expectedDS
      }

    "return details of the dataset with the given id " +
      "- a case where unrelated projects are sharing a dataset and one of has a fork" in new TestCase {

        val commonSameAs = datasetExternalSameAs.generateOne
        val (dataset1, project1) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .generateOne
        val (dataset2, project2 -> project2Fork) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .forkOnce()
          .generateOne

        provisionTestProjects(project1, project2, project2Fork).unsafeRunSync()

        val expectedUsedIns = List(toDatasetProject(project1, dataset1),
                                   toDatasetProject(project2, dataset2),
                                   toDatasetProject(project2Fork, dataset2)
        ).sorted
        val expectedDS1 = importedExternalToNonModified(dataset1, project1).copy(usedIn = expectedUsedIns.sorted)
        findById(dataset1.identifier, project1.slug).value shouldBe expectedDS1

        assume(project2.datasets === project2Fork.datasets, "Datasets on original project and fork should be the same")

        val expectedDS2 = importedExternalToNonModified(dataset2, project2).copy(usedIn = expectedUsedIns.sorted)
        findById(dataset2.identifier, project2.slug).value shouldBe expectedDS2

        val expectedDS2Fork =
          importedExternalToNonModified(dataset2, project2Fork).copy(usedIn = expectedUsedIns.sorted)
        findBySameAs(commonSameAs, project1.slug, project2.slug, project2Fork.slug).value should
          (be(expectedDS1) or be(expectedDS2) or be(expectedDS2Fork))
      }

    "return details of the dataset with the given id " +
      "- a case where an Internal dataset is defined on a grandparent project with two levels of forks" in new TestCase {

        val dataset -> grandparent =
          anyRenkuProjectEntities(visibilityPublic).addDataset(datasetEntities(provenanceInternal)).generateOne
        val grandparentForked -> parent = grandparent.forkOnce()
        val parentForked -> child       = parent.forkOnce()

        provisionTestProjects(grandparentForked, parentForked, child).unsafeRunSync()

        assume(
          (grandparentForked.datasets === parentForked.datasets) && (parentForked.datasets === child.datasets),
          "Datasets on original project and forks have to be the same"
        )

        val expectedDS = internalToNonModified(dataset, grandparentForked)
          .copy(usedIn =
            List(toDatasetProject(grandparentForked, dataset),
                 toDatasetProject(parentForked, dataset),
                 toDatasetProject(child, dataset)
            ).sorted
          )
        findById(dataset.identifier, grandparentForked.slug).value                          shouldBe expectedDS
        findByTopmostSameAs(dataset.provenance.topmostSameAs, grandparentForked.slug).value shouldBe expectedDS
      }

    "return details of the modified dataset with the given id " +
      "- case where modification is followed by forking" in new TestCase {

        val (original -> modified, project -> fork) =
          anyRenkuProjectEntities(visibilityPublic)
            .addDatasetAndModification(datasetEntities(provenanceInternal))
            .forkOnce()
            .generateOne

        provisionTestProjects(project, fork).unsafeRunSync()

        findById(original.identifier, project.slug).value shouldBe
          internalToNonModified(original, project).copy(usedIn = Nil)

        val expectedUsedIns = List(toDatasetProject(project, modified), toDatasetProject(fork, modified)).sorted
        val expectedModified =
          modifiedToModified(modified, original.provenance.date, project).copy(usedIn = expectedUsedIns)
        findById(modified.identifier, project.slug).value shouldBe expectedModified

        val expectedModifiedFork =
          modifiedToModified(modified, original.provenance.date, fork).copy(usedIn = expectedUsedIns)
        findByTopmostSameAs(modified.provenance.topmostSameAs, project.slug, fork.slug).value should
          (be(expectedModified) or be(expectedModifiedFork))
      }

    "return details of the modified dataset with the given id " +
      "- case where modification is followed by forking and some other modification" in new TestCase {

        val (original -> modified, project -> fork) = anyRenkuProjectEntities(visibilityPublic)
          .addDatasetAndModification(datasetEntities(provenanceInternal))
          .forkOnce()
          .generateOne
        val (modifiedAgain, projectUpdated) = project.addDataset(modified.createModification())

        provisionTestProjects(projectUpdated, fork).unsafeRunSync()

        findById(original.identifier, projectUpdated.slug).value shouldBe
          internalToNonModified(original, projectUpdated).copy(usedIn = Nil)
        findByTopmostSameAs(original.provenance.topmostSameAs, projectUpdated.slug) shouldBe None

        val expectedModified =
          modifiedToModified(modified, original.provenance.date, projectUpdated)
            .copy(usedIn = List(toDatasetProject(fork, modified)))
        findById(modified.identifier, projectUpdated.slug).value                    shouldBe expectedModified
        findByTopmostSameAs(modified.provenance.topmostSameAs, projectUpdated.slug) shouldBe None

        val expectedLastModification = modifiedToModified(modifiedAgain, original.provenance.date, projectUpdated)
        findById(modifiedAgain.identifier, projectUpdated.slug).value shouldBe expectedLastModification
        findByTopmostSameAs(modifiedAgain.provenance.topmostSameAs,
                            projectUpdated.slug
        ).value shouldBe expectedLastModification
      }

    "return details of the dataset with the given id " +
      "- case where forking is followed by modification" in new TestCase {

        val (original, project -> fork) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceInternal))
          .forkOnce()
          .generateOne
        val (modifiedOnFork, forkUpdated) = fork.addDataset(original.createModification())

        provisionTestProjects(project, forkUpdated).unsafeRunSync()

        val expectedOriginal = internalToNonModified(original, project)
        findById(original.identifier, project.slug).value                          shouldBe expectedOriginal
        findByTopmostSameAs(original.provenance.topmostSameAs, project.slug).value shouldBe expectedOriginal

        val expectedModificationOnFork = modifiedToModified(modifiedOnFork, original.provenance.date, forkUpdated)
        findById(modifiedOnFork.identifier, forkUpdated.slug).value shouldBe expectedModificationOnFork
        findByTopmostSameAs(modifiedOnFork.provenance.topmostSameAs,
                            forkUpdated.slug
        ).value shouldBe expectedModificationOnFork
      }

    "return details of the dataset with the given id " +
      "- case when a dataset on a fork is deleted" in new TestCase {

        val (original, project -> fork) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceInternal))
          .forkOnce()
          .generateOne
        val invalidation         = original.invalidateNow(personEntities)
        val forkWithInvalidation = fork.addDatasets(invalidation)

        provisionTestProjects(project, forkWithInvalidation).unsafeRunSync()

        val expectedOriginal = internalToNonModified(original, project)
        findById(original.identifier, project.slug).value                          shouldBe expectedOriginal
        findByTopmostSameAs(original.provenance.topmostSameAs, project.slug).value shouldBe expectedOriginal

        findById(invalidation.identifier, project.slug) shouldBe None
      }

    "return details of a fork dataset with the given id " +
      "- case where the dataset on the parent is deleted" in new TestCase {

        val (original, project -> fork) =
          anyRenkuProjectEntities(visibilityPublic)
            .addDataset(datasetEntities(provenanceInternal))
            .forkOnce()
            .generateOne
        val invalidation            = original.invalidateNow(personEntities)
        val projectWithInvalidation = project.addDatasets(invalidation)

        provisionTestProjects(projectWithInvalidation, fork).unsafeRunSync()

        val expectedOriginal = internalToNonModified(original, fork)
        findById(original.identifier, fork.slug).value                          shouldBe expectedOriginal
        findByTopmostSameAs(original.provenance.topmostSameAs, fork.slug).value shouldBe expectedOriginal

        findById(invalidation.identifier, fork.slug) shouldBe None
      }

    "return details of the dataset with the given id " +
      "- a case where the user has no access to the original project" in new TestCase {

        val (originalDataset, originalProject -> fork) = renkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceInternal))
          .forkOnce()
          .generateOne

        provisionTestProjects(originalProject, fork).unsafeRunSync()

        assume(originalProject.datasets === fork.datasets,
               "Datasets on original project and its fork should be the same"
        )

        val expectedDS = internalToNonModified(originalDataset, fork)
          .copy(usedIn =
            List(toDatasetProject(originalProject, originalDataset), toDatasetProject(fork, originalDataset)).sorted
          )
        findById(originalDataset.identifier, fork.slug).value                          shouldBe expectedDS
        findByTopmostSameAs(originalDataset.provenance.topmostSameAs, fork.slug).value shouldBe expectedDS
      }
  }

  "findDataset in the case of dataset import hierarchy" should {

    "return details of the dataset with the given id " +
      "- a case where the first dataset is an External dataset" in new TestCase {

        val commonSameAs = datasetExternalSameAs.generateOne
        val (dataset1, project1) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .generateOne
        val (dataset2, project2) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(datasetEntities(provenanceImportedExternal(commonSameAs)))
          .generateOne
        val (dataset3, project3) = anyRenkuProjectEntities(visibilityPublic).importDataset(dataset2).generateOne

        provisionTestProjects(project1, project2, project3).unsafeRunSync()

        val expectedUsedIns = List(toDatasetProject(project1, dataset1),
                                   toDatasetProject(project2, dataset2),
                                   toDatasetProject(project3, dataset3)
        ).sorted
        val expectedDS1 = importedExternalToNonModified(dataset1, project1).copy(usedIn = expectedUsedIns)
        findById(dataset1.identifier, project1.slug).value shouldBe expectedDS1

        val expectedDS3 = importedInternalToNonModified(dataset3, project3).copy(
          sameAs = dataset2.provenance.sameAs,
          usedIn = expectedUsedIns
        )
        findById(dataset3.identifier, project3.slug).value shouldBe expectedDS3

        val expectedDS2 = importedExternalToNonModified(dataset2, project2).copy(usedIn = expectedUsedIns)
        findBySameAs(commonSameAs, List(project1, project2, project3).map(_.slug): _*).value should
          (be(expectedDS1) or be(expectedDS2) or be(expectedDS3))
      }

    "return details of the dataset with the given id " +
      "- case where the first dataset is an Internal dataset" in new TestCase {

        val (dataset1, project1) =
          anyRenkuProjectEntities(visibilityPublic).addDataset(datasetEntities(provenanceInternal)).generateOne
        val (dataset2, project2) = anyRenkuProjectEntities(visibilityPublic).importDataset(dataset1).generateOne
        val (dataset3, project3) = anyRenkuProjectEntities(visibilityPublic).importDataset(dataset2).generateOne

        provisionTestProjects(project1, project2, project3).unsafeRunSync()

        val expectedUsedIns = List(toDatasetProject(project1, dataset1),
                                   toDatasetProject(project2, dataset2),
                                   toDatasetProject(project3, dataset3)
        ).sorted
        val expectedDS1 = internalToNonModified(dataset1, project1).copy(usedIn = expectedUsedIns)
        findById(dataset1.identifier, project1.slug).value shouldBe expectedDS1

        val expectedDS3 = importedInternalToNonModified(dataset3, project3).copy(
          sameAs = SameAs(dataset1.provenance.entityId),
          usedIn = expectedUsedIns
        )
        findById(dataset3.identifier, project3.slug).value shouldBe expectedDS3

        val expectedDS2 = importedInternalToNonModified(dataset2, project2).copy(usedIn = expectedUsedIns)
        findByTopmostSameAs(dataset1.provenance.topmostSameAs,
                            List(project1, project2, project3).map(_.slug): _*
        ).value should (be(expectedDS1) or be(expectedDS2) or be(expectedDS3))
      }

    "return details of the dataset with the given id " +
      "- case where the sameAs hierarchy is broken by dataset modification" in new TestCase {

        val (dataset1, project1) =
          anyRenkuProjectEntities(visibilityPublic).addDataset(datasetEntities(provenanceInternal)).generateOne
        val (dataset2, project2) = anyRenkuProjectEntities(visibilityPublic).importDataset(dataset1).generateOne
        val (dataset2Modified, project2Updated) = project2.addDataset(dataset2.createModification())
        val (dataset3, project3) = anyRenkuProjectEntities(visibilityPublic).importDataset(dataset2Modified).generateOne

        provisionTestProjects(project1, project2Updated, project3).unsafeRunSync()

        val expectedDS1 = internalToNonModified(dataset1, project1)
        findById(dataset1.identifier, project1.slug).value                          shouldBe expectedDS1
        findByTopmostSameAs(dataset1.provenance.topmostSameAs, project1.slug).value shouldBe expectedDS1

        val expectedDS2Modified = modifiedToModified(dataset2Modified, dataset1.provenance.date, project2Updated)
          .copy(usedIn =
            List(toDatasetProject(project2Updated, dataset2Modified), toDatasetProject(project3, dataset3)).sorted
          )
        findById(dataset2Modified.identifier, project2Updated.slug).value shouldBe expectedDS2Modified
        findByTopmostSameAs(dataset2Modified.provenance.topmostSameAs,
                            project2Updated.slug
        ).value shouldBe expectedDS2Modified
      }

    "not return details of a dataset" +
      "- case where the latest import of the dataset has been invalidated" in new TestCase {

        val (dataset1, project1) =
          anyRenkuProjectEntities(visibilityPublic).addDataset(datasetEntities(provenanceInternal)).generateOne
        val (dataset2, project2) = anyRenkuProjectEntities(visibilityPublic).importDataset(dataset1).generateOne
        val dataset2Invalidation = dataset2.invalidateNow(personEntities)
        val project2Updated      = project2.addDatasets(dataset2Invalidation)

        provisionTestProjects(project1, project2Updated).unsafeRunSync()

        val expectedDS1 = internalToNonModified(dataset1, project1)
        findById(dataset1.identifier, project1.slug).value                          shouldBe expectedDS1
        findByTopmostSameAs(dataset1.provenance.topmostSameAs, project1.slug).value shouldBe expectedDS1

        findById(dataset2.identifier, project2.slug)                          shouldBe None
        findByTopmostSameAs(dataset2.provenance.topmostSameAs, project2.slug) shouldBe None
        findById(dataset2Invalidation.identifier, project2.slug)              shouldBe None
      }

    "not return details of a dataset" +
      "- case where the original dataset has been invalidated" in new TestCase {

        val (dataset1, project1) =
          anyRenkuProjectEntities(visibilityPublic).addDataset(datasetEntities(provenanceInternal)).generateOne
        val (dataset2, project2) = anyRenkuProjectEntities(visibilityPublic).importDataset(dataset1).generateOne
        val dataset1Invalidation = dataset1.invalidateNow(personEntities)
        val project1Updated      = project1.addDatasets(dataset1Invalidation)

        provisionTestProjects(project1Updated, project2).unsafeRunSync()

        findById(dataset1.identifier, project1.slug)                          shouldBe None
        findByTopmostSameAs(dataset1.provenance.topmostSameAs, project1.slug) shouldBe None
        findById(dataset1Invalidation.identifier, project1.slug)              shouldBe None

        val expectedDS2 = importedInternalToNonModified(dataset2, project2)
        findById(dataset2.identifier, project2.slug).value                          shouldBe expectedDS2
        findByTopmostSameAs(dataset2.provenance.topmostSameAs, project2.slug).value shouldBe expectedDS2
      }

    "not return details of a dataset" +
      "- case where the latest modification of the dataset has been invalidated" in new TestCase {

        val (dataset -> datasetModified, project) = anyRenkuProjectEntities(visibilityPublic)
          .addDatasetAndModification(datasetEntities(provenanceInternal))
          .generateOne
        val datasetInvalidation = datasetModified.invalidateNow(personEntities)
        val projectUpdated      = project.addDatasets(datasetInvalidation)

        provisionTestProject(projectUpdated).unsafeRunSync()

        findById(dataset.identifier, project.slug).value shouldBe internalToNonModified(dataset, projectUpdated)
          .copy(usedIn = Nil)
        findByTopmostSameAs(dataset.provenance.topmostSameAs, project.slug) shouldBe None

        findById(datasetModified.identifier, project.slug)                          shouldBe None
        findByTopmostSameAs(datasetModified.provenance.topmostSameAs, project.slug) shouldBe None

        findById(datasetInvalidation.identifier, projectUpdated.slug)                          shouldBe None
        findByTopmostSameAs(datasetInvalidation.provenance.topmostSameAs, projectUpdated.slug) shouldBe None
      }
  }

  "findDataset in the case the DS was imported from a tag" should {

    "return info about the initial tag if it was imported from a tag on another Renku DS " +
      "which means there are PublicationEvents on both original and imported DS with the same name and desc, " +
      "there's sameAs on the imported DS to the original DS " +
      "and imported DS has schema:version the same as the PublicationEvent name" in new TestCase {

        val (originalDS, originalDSProject) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(
            datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(List(publicationEventFactory)))
          )
          .generateOne

        val originalDSTag = originalDS.publicationEvents.head

        val (importedDS, importedDSProject) =
          anyRenkuProjectEntities(visibilityPublic).importDataset(originalDSTag).generateOne

        provisionTestProjects(originalDSProject, importedDSProject).unsafeRunSync()

        val expectedUsedIns =
          List(toDatasetProject(originalDSProject, originalDS), toDatasetProject(importedDSProject, importedDS)).sorted
        val expectedImportedDS = importedInternalToNonModified(importedDS, importedDSProject).copy(
          maybeInitialTag = Tag(originalDSTag.name, originalDSTag.maybeDescription).some,
          usedIn = expectedUsedIns
        )
        findById(importedDS.identifier,
                 originalDSProject.slug,
                 importedDSProject.slug
        ).value shouldBe expectedImportedDS

        val expectedOriginalDS = internalToNonModified(originalDS, originalDSProject).copy(usedIn = expectedUsedIns)
        findByTopmostSameAs(importedDS.provenance.topmostSameAs,
                            originalDSProject.slug,
                            importedDSProject.slug
        ).value should (be(expectedImportedDS) or be(expectedOriginalDS))
      }

    "not return info about the initial tag even if it was imported from a tag on another Renku DS " +
      "but the user has no access to the project of the original DS anymore" in new TestCase {

        val (originalDS, originalDSProject) = anyRenkuProjectEntities(visibilityPrivate)
          .addDataset(
            datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(List(publicationEventFactory)))
          )
          .generateOne

        val originalDSTag = originalDS.publicationEvents.head

        val (importedDS, importedDSProject) =
          anyRenkuProjectEntities(visibilityPublic).importDataset(originalDSTag).generateOne

        provisionTestProjects(originalDSProject, importedDSProject).unsafeRunSync()

        val expectedDS = importedInternalToNonModified(importedDS, importedDSProject)
        findById(importedDS.identifier, importedDSProject.slug).value                          shouldBe expectedDS
        findByTopmostSameAs(importedDS.provenance.topmostSameAs, importedDSProject.slug).value shouldBe expectedDS
      }

    "not return info about the initial tag if " +
      "there are PublicationEvents with the same name and desc on both original and imported DS " +
      "but the version on the imported DS is different that the event name" in new TestCase {

        val (originalDS, originalDSProject) = anyRenkuProjectEntities(visibilityPublic)
          .addDataset(
            datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(List(publicationEventFactory)))
          )
          .generateOne

        val originalDSTag = originalDS.publicationEvents.head

        val (importedDS, importedDSProject) = {
          val (ds, proj)    = anyRenkuProjectEntities(visibilityPublic).importDataset(originalDS).generateOne
          val dsWithSameTag = ds.copy(publicationEventFactories = List(originalDSTag.toFactory))
          dsWithSameTag -> proj.replaceDatasets(dsWithSameTag)
        }

        provisionTestProjects(originalDSProject, importedDSProject).unsafeRunSync()

        originalDSTag.name             shouldBe importedDS.publicationEvents.head.name
        originalDSTag.maybeDescription shouldBe importedDS.publicationEvents.head.maybeDescription

        val expectedImportedDS = importedInternalToNonModified(importedDS, importedDSProject).copy(
          maybeInitialTag = None,
          usedIn = List(toDatasetProject(originalDSProject, originalDS),
                        toDatasetProject(importedDSProject, importedDS)
          ).sorted
        )
        findById(importedDS.identifier,
                 originalDSProject.slug,
                 importedDSProject.slug
        ).value shouldBe expectedImportedDS

        val expectedOriginalDS = internalToNonModified(originalDS, originalDSProject).copy(
          maybeInitialTag = None,
          usedIn = List(toDatasetProject(originalDSProject, originalDS),
                        toDatasetProject(importedDSProject, importedDS)
          ).sorted
        )
        findByTopmostSameAs(importedDS.provenance.topmostSameAs,
                            originalDSProject.slug,
                            importedDSProject.slug
        ).value should (be(expectedImportedDS) or be(expectedOriginalDS))
      }
  }

  implicit override val ioLogger: TestLogger[IO] = TestLogger[IO]()

  private trait TestCase {
    implicit val renkuUrl:             RenkuUrl                    = renkuUrls.generateOne
    private implicit val timeRecorder: SparqlQueryTimeRecorder[IO] = TestSparqlQueryTimeRecorder[IO].unsafeRunSync()
    private val datasetFinder = new DatasetFinderImpl[IO](
      new BaseDetailsFinderImpl[IO](projectsDSConnectionInfo),
      new CreatorsFinderImpl[IO](projectsDSConnectionInfo),
      new PartsFinderImpl[IO](projectsDSConnectionInfo),
      new ProjectsFinderImpl[IO](projectsDSConnectionInfo)
    )

    def findById(identifier: Identifier, authUser: AuthUser, allowedProjects: projects.Slug*): Option[details.Dataset] =
      findDS(RequestedDataset(identifier), authUser, allowedProjects: _*)
    def findById(identifier: Identifier, allowedProjects: projects.Slug*): Option[details.Dataset] =
      findDS(RequestedDataset(identifier), allowedProjects: _*)

    def findBySameAs(sameAs: SameAs, allowedProjects: projects.Slug*): Option[details.Dataset] =
      findDS(RequestedDataset(sameAs), allowedProjects: _*)

    def findByTopmostSameAs(topmostSameAs: TopmostSameAs, allowedProjects: projects.Slug*): Option[details.Dataset] =
      findDS(RequestedDataset(SameAs.ofUnsafe(topmostSameAs.value)), allowedProjects: _*)
    def findByTopmostSameAs(topmostSameAs:   TopmostSameAs,
                            authUser:        AuthUser,
                            allowedProjects: projects.Slug*
    ): Option[details.Dataset] =
      findDS(RequestedDataset(SameAs.ofUnsafe(topmostSameAs.value)), authUser, allowedProjects: _*)

    private def findDS(requestedDS: RequestedDataset, allowedProjects: projects.Slug*): Option[details.Dataset] =
      findDS(requestedDS, AuthContext.forUnknownUser(requestedDS, allowedProjects.toSet))

    private def findDS(requestedDS:     RequestedDataset,
                       authUser:        AuthUser,
                       allowedProjects: projects.Slug*
    ): Option[details.Dataset] =
      findDS(requestedDS, AuthContext(authUser.some, requestedDS, allowedProjects.toSet))

    private def findDS(requestedDS: RequestedDataset,
                       authContext: AuthContext[RequestedDataset]
    ): Option[details.Dataset] =
      datasetFinder
        .findDataset(requestedDS, authContext)
        .unsafeRunSync()

    ioLogger.reset()
  }

  private implicit lazy val usedInOrdering: Ordering[DatasetProject] = Ordering.by(_.name)
}
