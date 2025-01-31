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

import cats.effect.IO
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.entities.searchgraphs.SearchInfoDatasets
import io.renku.generators.CommonGraphGenerators._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.projects.Visibility
import io.renku.graph.model.testentities.{Dataset, Person, RenkuProject}
import io.renku.graph.model.testentities.generators.EntitiesGenerators
import io.renku.http.rest.SortBy.Direction
import io.renku.http.rest.Sorting
import io.renku.http.rest.paging.PagingRequest
import io.renku.http.rest.paging.model.{Page, PerPage, Total}
import io.renku.http.server.security.model.AuthUser
import io.renku.interpreters.TestLogger
import io.renku.knowledgegraph.datasets.DatasetSearchResult._
import io.renku.knowledgegraph.datasets.Endpoint.Query.Phrase
import io.renku.knowledgegraph.datasets.Endpoint.Sort
import io.renku.knowledgegraph.datasets.Endpoint.Sort._
import io.renku.logging.TestSparqlQueryTimeRecorder
import io.renku.testtools.IOSpec
import io.renku.triplesstore._
import org.scalacheck.Gen
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.Logger

class DatasetsFinderSpec
    extends AnyWordSpec
    with should.Matchers
    with EntitiesGenerators
    with InMemoryJenaForSpec
    with ProjectsDataset
    with SearchInfoDatasets
    with ScalaCheckPropertyChecks
    with IOSpec {

  implicit override val ioLogger: Logger[IO] = TestLogger()

  "findDatasets - no phrase" should {

    Option(Phrase("*")) :: Option.empty[Phrase] :: Nil foreach { maybePhrase =>
      s"return all datasets when the given phrase is $maybePhrase " +
        "- case of datasets that has neither sameAs nor are imported to and/or from other projects" in new TestCase {

          val (dataset1, project1) = publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne
          val (dataset1ImportedToProject2, project2) = publicProjectEntities.importDataset(dataset1).generateOne
          val (dataset3, project3) = publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne

          provisionTestProjects(project1, project2, project3).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          val expectedResults = List(
            List(
              dataset1                   -> project1,
              dataset1ImportedToProject2 -> project2
            ).toDatasetSearchResult(matchIdFrom = result.results, projectsCount = 2),
            List((dataset3 -> project3).toDatasetSearchResult(projectsCount = 1))
          ).flatten.sortBy(_.title)

          result.results          shouldBe expectedResults
          result.pagingInfo.total shouldBe Total(expectedResults.size)
        }

      s"return all datasets when the given phrase is $maybePhrase " +
        "- case of non-modified datasets" in new TestCase {

          val projects = renkuProjectEntities(visibilityPublic)
            .withDatasets(datasetEntities(provenanceImportedExternal))
            .generateNonEmptyList(max = PagingRequest.default.perPage.value)
            .toList

          provisionTestProjects(projects: _*).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe projects
            .map(project => project.datasets.head -> project)
            .map(_.toDatasetSearchResult(projectsCount = 1))
            .sortBy(_.title)
          result.pagingInfo.total shouldBe Total(projects.size)
        }

      s"return all datasets when the given phrase is $maybePhrase " +
        "- case of shared sameAs" in new TestCase {

          val dataset              = datasetEntities(provenanceImportedExternal).decoupledFromProject.generateOne
          val (dataset1, project1) = publicProjectEntities.importDataset(dataset).generateOne
          val (dataset2, project2) = publicProjectEntities.importDataset(dataset).generateOne
          val (dataset3, project3) = publicProjectEntities.importDataset(dataset).generateOne

          provisionTestProjects(project1, project2, project3).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            (dataset1, project1),
            (dataset2, project2),
            (dataset3, project3)
          ).toDatasetSearchResult(matchIdFrom = result.results, projectsCount = 3).toList
          result.pagingInfo.total shouldBe Total(1)
        }

      s"return all datasets when the given phrase is $maybePhrase " +
        "- case of shared sameAs with modification on some projects" in new TestCase {
          val dataset              = datasetEntities(provenanceImportedExternal).decoupledFromProject.generateOne
          val (dataset1, project1) = publicProjectEntities.importDataset(dataset).generateOne
          val (dataset2, project2) = publicProjectEntities.importDataset(dataset).generateOne
          val (dataset2Modified, project2Updated) = project2.addDataset(dataset2.createModification())

          provisionTestProjects(project1).unsafeRunSync()
          provisionTestProjects(project2Updated).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            (dataset1, project1),
            (dataset2Modified, project2Updated)
          ).map(_.toDatasetSearchResult(projectsCount = 1)).sortBy(_.title)
          result.pagingInfo.total shouldBe Total(2)
        }

      s"return all datasets when the given phrase is $maybePhrase " +
        "- case of shared sameAs and forks" in new TestCase {

          val dataset              = datasetEntities(provenanceImportedExternal).decoupledFromProject.generateOne
          val (dataset1, project1) = publicProjectEntities.importDataset(dataset).generateOne
          val (dataset2, project2 -> project2Fork) =
            publicProjectEntities.importDataset(dataset).forkOnce().generateOne

          provisionTestProjects(project1, project2, project2Fork).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            (dataset1, project1),
            (dataset2, project2)
          ).toDatasetSearchResult(matchIdFrom = result.results,
                                  projectsCount = 3,
                                  matchProjectFrom = List(project1, project2, project2Fork)
          ).toList
          result.pagingInfo.total shouldBe Total(1)
        }

      s"return latest versions of datasets when the given phrase is $maybePhrase " +
        "- case of one level of modification" in new TestCase {

          val ((_, dataset1Modified), project1) =
            publicProjectEntities.addDatasetAndModification(datasetEntities(provenanceImportedExternal)).generateOne
          val ((_, dataset2Modified), project2) =
            publicProjectEntities.addDatasetAndModification(datasetEntities(provenanceImportedExternal)).generateOne

          provisionTestProjects(project1, project2).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(dataset1Modified -> project1, dataset2Modified -> project2)
            .map(_.toDatasetSearchResult(projectsCount = 1))
            .sortBy(_.title)
          result.pagingInfo.total shouldBe Total(2)
        }

      s"return latest versions of datasets when the given phrase is $maybePhrase " +
        "- case more than one level of modification" in new TestCase {

          val (_ -> modification1, project) =
            publicProjectEntities.addDatasetAndModification(datasetEntities(provenanceInternal)).generateOne
          val (modification2, projectWithAllDatasets) = project.addDataset(modification1.createModification())

          provisionTestProjects(projectWithAllDatasets).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List((modification2, projectWithAllDatasets).toDatasetSearchResult(projectsCount = 1))
          result.pagingInfo.total shouldBe Total(1)
        }

      s"return latest versions of datasets when the given phrase is $maybePhrase " +
        "- case if there are modified and non-modified datasets" in new TestCase {

          val (_ -> dataset1Modification, project1) =
            publicProjectEntities.addDatasetAndModification(datasetEntities(provenanceInternal)).generateOne
          val (dataset2, project2) =
            publicProjectEntities.addDataset(datasetEntities(provenanceImportedExternal)).generateOne

          provisionTestProjects(project1, project2).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            dataset1Modification -> project1,
            dataset2             -> project2
          ).map(_.toDatasetSearchResult(projectsCount = 1)).sortBy(_.title)
          result.pagingInfo.total shouldBe Total(2)
        }

      s"return latest versions of datasets when the given phrase is $maybePhrase " +
        "- case if shared datasets are modified on some projects but not all" in new TestCase {

          val dataset              = datasetEntities(provenanceImportedExternal).decoupledFromProject.generateOne
          val (dataset1, project1) = publicProjectEntities.importDataset(dataset).generateOne
          val (dataset2, project2) = publicProjectEntities.importDataset(dataset).generateOne
          val (dataset2Modified, project2Updated) = project2.addDataset(dataset2.createModification())

          provisionTestProjects(project1, project2Updated).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(None, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            dataset1         -> project1,
            dataset2Modified -> project2Updated
          ).map(_.toDatasetSearchResult(projectsCount = 1)).sortBy(_.title)
          result.pagingInfo.total shouldBe Total(2)
        }

      s"return latest versions of datasets when the given phrase is $maybePhrase " +
        "- case with forks on renku created datasets" in new TestCase {

          val (dataset, project -> fork) = publicProjectEntities
            .addDataset(datasetEntities(provenanceInternal))
            .forkOnce()
            .generateOne

          provisionTestProjects(project, fork).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            dataset.toDatasetSearchResult(projectsCount = 2, result.results, List(project, fork))
          )
          result.pagingInfo.total shouldBe Total(1)
        }

      s"return latest versions of datasets when the given phrase is $maybePhrase " +
        "- case with more than one level of modification and forks on the 1st level" in new TestCase {

          val (dataset, project -> fork) =
            publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).forkOnce().generateOne
          val (datasetModified, projectUpdated) = project.addDataset(dataset.createModification())

          provisionTestProjects(projectUpdated, fork).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            (dataset, fork).toDatasetSearchResult(projectsCount = 1),
            (datasetModified, projectUpdated).toDatasetSearchResult(projectsCount = 1)
          ).sortBy(_.title)

          result.pagingInfo.total shouldBe Total(2)
        }

      s"return latest versions of datasets when the given phrase is $maybePhrase " +
        "- case with more than one level of modification and forks not on the 1st level" in new TestCase {

          val (_ -> datasetModification, project -> fork) =
            publicProjectEntities
              .addDatasetAndModification(datasetEntities(provenanceImportedExternal))
              .forkOnce()
              .generateOne
          val (modificationOfModificationOnFork, forkUpdated) =
            fork.addDataset(datasetModification.createModification())

          provisionTestProjects(project, forkUpdated).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List(
            (datasetModification, project).toDatasetSearchResult(projectsCount = 1),
            (modificationOfModificationOnFork, forkUpdated).toDatasetSearchResult(projectsCount = 1)
          ).sortBy(_.title)
          result.pagingInfo.total shouldBe Total(2)
        }

      s"not return deleted datasets when the given phrase is $maybePhrase" +
        "- case with unrelated datasets" in new TestCase {
          val (dataset1, project1) =
            publicProjectEntities.addDataset(datasetEntities(provenanceImportedExternal)).generateOne
          val (_, project2) =
            publicProjectEntities.addDatasetAndInvalidation(datasetEntities(provenanceImportedExternal)).generateOne

          provisionTestProjects(project1, project2).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List((dataset1, project1).toDatasetSearchResult(projectsCount = 1))
        }

      s"not return deleted datasets when the given phrase is $maybePhrase" +
        "- case with forks on renku created datasets and the fork dataset is deleted" in new TestCase {

          val (dataset, project -> fork) =
            publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).forkOnce().generateOne
          val forkUpdated = fork.addDatasets(dataset.invalidateNow(personEntities))

          provisionTestProjects(project, forkUpdated).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List((dataset, project).toDatasetSearchResult(projectsCount = 1))
        }

      s"not return deleted datasets when the given phrase is $maybePhrase" +
        "- case with forks on renku created datasets and original dataset is deleted" in new TestCase {

          val (dataset, project -> fork) =
            publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).forkOnce().generateOne
          val afterForkingUpdated = project.addDatasets(dataset.invalidateNow(personEntities))

          provisionTestProjects(afterForkingUpdated, fork).unsafeRunSync()

          val result = datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()

          result.results shouldBe List((dataset, fork).toDatasetSearchResult(projectsCount = 1))
        }

      s"not return deleted datasets when the given phrase is $maybePhrase" +
        "- case with modification on renku created datasets" in new TestCase {

          val (_ -> datasetModified, project) =
            publicProjectEntities.addDatasetAndModification(datasetEntities(provenanceInternal)).generateOne
          val projectUpdated = project.addDatasets(datasetModified.invalidateNow(personEntities))

          provisionTestProjects(projectUpdated).unsafeRunSync()

          datasetsFinder
            .findDatasets(maybePhrase, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
            .unsafeRunSync()
            .results shouldBe Nil
        }
    }
  }

  "findDatasets - some phrase given" should {

    "returns all datasets containing the phrase - " +
      "case with no shared SameAs and no modifications" in new TestCase {

        val phrase = phrases.generateOne.value
        val (dataset1, project1) = publicProjectEntities
          .addDataset(datasetEntities(provenanceInternal).modify(_.makeNameContaining(phrase)))
          .generateOne
        val (dataset2, project2) = publicProjectEntities
          .addDataset(datasetEntities(provenanceInternal).modify(_.makeDescContaining(phrase)))
          .generateOne
        val (dataset3, project3) = publicProjectEntities
          .addDataset(datasetEntities(provenanceInternal).modify(_.makeCreatorNameContaining(phrase)))
          .generateOne
        val (dataset4, project4) = publicProjectEntities
          .addDataset(datasetEntities(provenanceInternal).modify(_.makeTitleContaining(phrase)))
          .generateOne
        val (dataset5, project5) = publicProjectEntities
          .addDataset(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase)))
          .generateOne
        val (_, projectWithoutPhrase) =
          publicProjectEntities.addDataset(datasetEntities(provenanceNonModified)).generateOne

        provisionTestProjects(project1, project2, project3, project4, project5, projectWithoutPhrase).unsafeRunSync()

        val result = datasetsFinder
          .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
          .unsafeRunSync()

        result.results shouldBe List((dataset1, project1),
                                     (dataset2, project2),
                                     (dataset3, project3),
                                     (dataset4, project4),
                                     (dataset5, project5)
        ).map(_.toDatasetSearchResult(projectsCount = 1)).sortBy(_.title)
        result.pagingInfo.total shouldBe Total(5)
      }

    "return no results if there is no matching dataset" in new TestCase {

      val (_, project) = publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne

      provisionTestProjects(project).unsafeRunSync()

      datasetsFinder
        .findDatasets(Some(phrases.generateOne),
                      Sorting(Sort.By(TitleProperty, Direction.Asc)),
                      PagingRequest.default,
                      None
        )
        .unsafeRunSync()
        .results shouldBe Nil
    }

    "return no datasets if the match was only in an older version which is not used anymore" in new TestCase {

      val phrase = phrases.generateOne
      val (_, project) = publicProjectEntities
        .addDatasetAndModification(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne

      provisionTestProjects(project).unsafeRunSync()

      datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results shouldBe Nil
    }

    "return datasets matching the criteria excluding datasets which were modified and does not match anymore" in new TestCase {

      val phrase = phrases.generateOne
      val (dataset1, project1) = publicProjectEntities
        .addDataset(datasetEntities(provenanceImportedExternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne
      val (_, project2) = publicProjectEntities
        .addDatasetAndModification(
          datasetEntities(provenanceImportedExternal).modify(_.makeKeywordsContaining(phrase.value))
        )
        .generateOne

      provisionTestProjects(project1, project2).unsafeRunSync()

      datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results shouldBe List((dataset1, project1).toDatasetSearchResult(projectsCount = 1))
    }

    "return datasets matching the criteria after modification" in new TestCase {

      val phrase = phrases.generateOne
      val (_, project1) =
        publicProjectEntities.addDataset(datasetEntities(provenanceImportedExternal)).generateOne
      val (dataset2, project2) =
        publicProjectEntities.addDataset(datasetEntities(provenanceImportedExternal)).generateOne
      val (dataset2Modified, project2Updated) =
        project2.addDataset(dataset2.createModification(_.makeKeywordsContaining(phrase.value)))

      provisionTestProjects(project1, project2Updated).unsafeRunSync()

      datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results shouldBe List((dataset2Modified, project2Updated).toDatasetSearchResult(projectsCount = 1))
    }

    "return no datasets if the criteria is matched somewhere in the middle of the modification hierarchy" in new TestCase {

      val phrase             = phrases.generateOne
      val (dataset, project) = publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne
      val (datasetModification1, projectUpdate1) =
        project.addDataset(dataset.createModification(_.makeKeywordsContaining(phrase.value)))
      val (_, projectUpdate2) = projectUpdate1.addDataset(datasetModification1.createModification())

      provisionTestProjects(projectUpdate2).unsafeRunSync()

      datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results shouldBe Nil
    }

    "return datasets matching the criteria excluding datasets which were modified on forks and does not match anymore" in new TestCase {

      val phrase = phrases.generateOne
      val (dataset, project -> fork) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase.value)))
        .forkOnce()
        .generateOne
      val _ -> forkUpdated = fork.addDataset(dataset.createModification())

      provisionTestProjects(project, forkUpdated).unsafeRunSync()

      datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results shouldBe List((dataset, project).toDatasetSearchResult(projectsCount = 1))
    }

    "return datasets matching the criteria after modification of the fork" in new TestCase {

      val phrase = phrases.generateOne
      val (dataset, project -> fork) =
        publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).forkOnce().generateOne
      val (datasetModified, forkUpdated) =
        fork.addDataset(dataset.createModification(_.makeKeywordsContaining(phrase.value)))

      provisionTestProjects(project, forkUpdated).unsafeRunSync()

      datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results shouldBe List((datasetModified, forkUpdated).toDatasetSearchResult(projectsCount = 1))
    }

    "not return deleted datasets even if the phrase match" +
      "- case with unrelated datasets" in new TestCase {

        val phrase = phrases.generateOne
        val (_, project) = publicProjectEntities
          .addDatasetAndInvalidation(datasetEntities(provenanceInternal).modify(_.makeDescContaining(phrase.value)))
          .generateOne

        provisionTestProjects(project).unsafeRunSync()

        datasetsFinder
          .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
          .unsafeRunSync()
          .results shouldBe Nil
      }
  }

  "findDatasets when explicit sorting given" should {

    s"return datasets with name, description or creator matching the given phrase sorted by $TitleProperty" in new TestCase {
      val phrase = phrases.generateOne
      val (dataset1, project1) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeTitleContaining(phrase.value)))
        .generateOne

      val (dataset2, project2) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeDescContaining(phrase.value)))
        .generateOne

      val (dataset3, project3) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne

      val (_, projectNonPhrased) = publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne

      provisionTestProjects(project1, project2, project3, projectNonPhrased).unsafeRunSync()

      val results = datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results

      results shouldBe List((dataset1, project1), (dataset2, project2), (dataset3, project3))
        .map(_.toDatasetSearchResult(1))
        .sortBy(_.title)

    }

    s"return datasets with name, description or creator matching the given phrase sorted by $DatePublishedProperty" in new TestCase {
      val phrase = phrases.generateOne
      val (dataset1, project1) = publicProjectEntities
        .addDataset(datasetEntities(provenanceImportedExternal).modify(_.makeTitleContaining(phrase.value)))
        .generateOne
      val (dataset2, project2) = publicProjectEntities
        .addDataset(datasetEntities(provenanceImportedExternal).modify(_.makeDescContaining(phrase.value)))
        .generateOne
      val (dataset3, project3) = publicProjectEntities
        .addDataset(datasetEntities(provenanceImportedExternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne

      provisionTestProjects(
        project1,
        project2,
        project3,
        publicProjectEntities.addDataset(datasetEntities(provenanceImportedExternal)).generateOne._2
      ).unsafeRunSync()

      val results = datasetsFinder
        .findDatasets(Some(phrase),
                      Sorting(Sort.By(DatePublishedProperty, Direction.Desc)),
                      PagingRequest.default,
                      None
        )
        .unsafeRunSync()
        .results

      results shouldBe List((dataset1, project1), (dataset2, project2), (dataset3, project3))
        .map(_.toDatasetSearchResult(projectsCount = 1))
        .sortBy(_.createdOrPublished.instant)
        .reverse
    }

    s"return datasets with name, description or creator matching the given phrase sorted by $DateProperty" in new TestCase {
      val phrase = phrases.generateOne
      val (dataset1, project1) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeTitleContaining(phrase.value)))
        .generateOne
      val (dataset2, project2) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeDescContaining(phrase.value)))
        .generateOne
      val (dataset3, project3) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne

      provisionTestProjects(
        project1,
        project2,
        project3,
        publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne._2
      ).unsafeRunSync()

      val results = datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(DateProperty, Direction.Desc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results

      results shouldBe List((dataset1, project1), (dataset2, project2), (dataset3, project3))
        .map(_.toDatasetSearchResult(projectsCount = 1))
        .sortBy(_.createdOrPublished.instant)
        .reverse
    }

    s"return datasets with name, description or creator matching the given phrase sorted by $ProjectsCountProperty" in new TestCase {
      val phrase = phrases.generateOne

      val (dataset1, project1 -> project1Fork) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeTitleContaining(phrase.value)))
        .forkOnce()
        .generateOne
      val (dataset2, project2 -> project2Forks) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeDescContaining(phrase.value)))
        .generateOne
        .map(_.fork(2))
      val (dataset3, project3) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne

      provisionTestProjects(
        project1 ::
          project1Fork ::
          project2 ::
          project2Forks.toList :::
          List(project3, publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne._2): _*
      ).unsafeRunSync()

      val results = datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(ProjectsCountProperty, Direction.Asc)), PagingRequest.default, None)
        .unsafeRunSync()
        .results

      results shouldBe List(
        dataset1.toDatasetSearchResult(projectsCount = 2, results, List(project1, project1Fork)),
        dataset2.toDatasetSearchResult(projectsCount = project2Forks.size + 1,
                                       results,
                                       project2 :: project2Forks.toList
        ),
        (dataset3, project3).toDatasetSearchResult(projectsCount = 1)
      ).sortBy(_.projectsCount)
    }
  }

  "findDatasets with explicit paging request" should {

    "return the requested page of datasets matching the given phrase" in new TestCase {
      val phrase = phrases.generateOne
      val (dataset1, project1) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeTitleContaining(phrase.value)))
        .generateOne
      val (dataset2, project2) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeDescContaining(phrase.value)))
        .generateOne
      val (dataset3, project3) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne

      provisionTestProjects(
        project1,
        project2,
        project3,
        publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne._2
      ).unsafeRunSync()

      val pagingRequest = PagingRequest(Page(2), PerPage(1))

      val result = datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), pagingRequest, None)
        .unsafeRunSync()

      result.results shouldBe List((dataset1, project1), (dataset2, project2), (dataset3, project3))
        .map(_.toDatasetSearchResult(projectsCount = 1))
        .sortBy(_.title)
        .get(1)
        .toList

      result.pagingInfo.pagingRequest shouldBe pagingRequest
      result.pagingInfo.total         shouldBe Total(3)
    }

    "return no results if the requested page does not exist" in new TestCase {
      val phrase = phrases.generateOne
      val (_, project1) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeTitleContaining(phrase.value)))
        .generateOne
      val (_, project2) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeDescContaining(phrase.value)))
        .generateOne
      val (_, project3) = publicProjectEntities
        .addDataset(datasetEntities(provenanceInternal).modify(_.makeKeywordsContaining(phrase.value)))
        .generateOne

      provisionTestProjects(project1, project2, project3).unsafeRunSync()

      val pagingRequest = PagingRequest(Page(2), PerPage(3))

      val result = datasetsFinder
        .findDatasets(Some(phrase), Sorting(Sort.By(TitleProperty, Direction.Asc)), pagingRequest, None)
        .unsafeRunSync()

      result.results                  shouldBe Nil
      result.pagingInfo.pagingRequest shouldBe pagingRequest
      result.pagingInfo.total         shouldBe Total(3)
    }
  }

  "findDatasets with unauthorized user" should {

    List(Visibility.Private, Visibility.Internal) foreach { nonPublic =>
      s"not return dataset from project with visibility $nonPublic" in new TestCase {
        val (publicDataset, publicProject) =
          publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne
        val (_, privateProject) =
          renkuProjectEntities(fixed(nonPublic)).addDataset(datasetEntities(provenanceInternal)).generateOne

        provisionTestProjects(publicProject, privateProject).unsafeRunSync()

        val result = datasetsFinder
          .findDatasets(None, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, maybeUser = None)
          .unsafeRunSync()

        result.results          shouldBe List((publicDataset, publicProject).toDatasetSearchResult(projectsCount = 1))
        result.pagingInfo.total shouldBe Total(1)
      }

      s"not count projects with visibility $nonPublic" in new TestCase {
        val (publicDataset, publicProject) =
          publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne

        val (_, privateProjectWithPublicDataset) =
          renkuProjectEntities(fixed(nonPublic)).importDataset(publicDataset).generateOne

        provisionTestProjects(publicProject, privateProjectWithPublicDataset).unsafeRunSync()

        val result = datasetsFinder
          .findDatasets(None, Sorting(Sort.By(TitleProperty, Direction.Asc)), PagingRequest.default, None)
          .unsafeRunSync()

        result.results          shouldBe List((publicDataset, publicProject).toDatasetSearchResult(projectsCount = 1))
        result.pagingInfo.total shouldBe Total(1)
      }
    }
  }

  "findDatasets with authorized user" should {

    "return datasets from public, internal and private project where the user is a member" in new TestCase {

      val (publicDataset, publicProject) =
        publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne

      val userWithGitlabId = projectMemberEntities(personGitLabIds.toGeneratorOfSomes).generateOne
      val (internalDatasetWithAccess, internalProjectWithAccess) =
        renkuProjectEntities(fixed(Visibility.Internal))
          .modify(_.copy(members = Set(userWithGitlabId)))
          .addDataset(datasetEntities(provenanceInternal))
          .generateOne

      val (privateDatasetWithAccess, privateProjectWithAccess) =
        renkuProjectEntities(fixed(Visibility.Private))
          .modify(_.copy(members = Set(userWithGitlabId)))
          .addDataset(datasetEntities(provenanceInternal))
          .generateOne

      provisionTestProjects(publicProject, internalProjectWithAccess, privateProjectWithAccess).unsafeRunSync()

      val result = datasetsFinder
        .findDatasets(maybePhrase = None,
                      Sorting(Sort.By(TitleProperty, Direction.Asc)),
                      PagingRequest.default,
                      userWithGitlabId.person.toAuthUser.some
        )
        .unsafeRunSync()

      val datasetsList = List(
        (publicDataset, publicProject).toDatasetSearchResult(projectsCount = 1),
        (internalDatasetWithAccess, internalProjectWithAccess).toDatasetSearchResult(projectsCount = 1),
        (privateDatasetWithAccess, privateProjectWithAccess).toDatasetSearchResult(projectsCount = 1)
      ).sortBy(_.title)

      result.results          shouldBe datasetsList
      result.pagingInfo.total shouldBe Total(3)
    }

    "return datasets from internal projects where the user is not a member" in new TestCase {

      val (internalDatasetWithoutAccess, internalProjectWithoutAccess) =
        renkuProjectEntities(fixed(Visibility.Internal)).addDataset(datasetEntities(provenanceInternal)).generateOne

      provisionTestProjects(internalProjectWithoutAccess).unsafeRunSync()

      datasetsFinder
        .findDatasets(maybePhrase = None,
                      Sorting(Sort.By(TitleProperty, Direction.Asc)),
                      PagingRequest.default,
                      authUsers.generateSome
        )
        .unsafeRunSync()
        .results shouldBe List(
        (internalDatasetWithoutAccess, internalProjectWithoutAccess).toDatasetSearchResult(projectsCount = 1)
      )
    }

    "not return datasets from private projects where the user is not a member" in new TestCase {

      val privateProjectWithoutAccess =
        renkuProjectEntities(fixed(Visibility.Private)).withDatasets(datasetEntities(provenanceInternal)).generateOne

      provisionTestProjects(privateProjectWithoutAccess).unsafeRunSync()

      datasetsFinder
        .findDatasets(maybePhrase = None,
                      Sorting(Sort.By(TitleProperty, Direction.Asc)),
                      PagingRequest.default,
                      authUsers.generateSome
        )
        .unsafeRunSync()
        .results shouldBe Nil
    }

    "return datasets from public, internal and private projects where the user is a member " +
      "but not count private projects the user is not a member" in new TestCase {

        val (publicDataset, publicProject) =
          publicProjectEntities.addDataset(datasetEntities(provenanceInternal)).generateOne
        val (internalDatasetWithoutAccess, internalProjectWithoutAccess) =
          renkuProjectEntities(fixed(Visibility.Internal)).addDataset(datasetEntities(provenanceInternal)).generateOne
        val (privateDatasetWithoutAccess, privateProjectWithoutAccess) =
          renkuProjectEntities(fixed(Visibility.Private)).addDataset(datasetEntities(provenanceInternal)).generateOne

        val userWithGitlabId = projectMemberEntities(personGitLabIds.toGeneratorOfSomes).generateOne
        val (internalDatasetWithAccess, internalProjectWithAccess) = renkuProjectEntities(fixed(Visibility.Internal))
          .modify(_.copy(members = Set(userWithGitlabId)))
          .importDataset(internalDatasetWithoutAccess)
          .generateOne
        val (privateDatasetWithAccess, privateProjectWithAccess) = renkuProjectEntities(fixed(Visibility.Private))
          .modify(_.copy(members = Set(userWithGitlabId)))
          .importDataset(privateDatasetWithoutAccess)
          .generateOne

        provisionTestProjects(
          publicProject,
          internalProjectWithoutAccess,
          internalProjectWithAccess,
          privateProjectWithoutAccess,
          privateProjectWithAccess
        ).unsafeRunSync()

        val result = datasetsFinder
          .findDatasets(maybePhrase = None,
                        Sorting(Sort.By(TitleProperty, Direction.Asc)),
                        PagingRequest.default,
                        userWithGitlabId.person.toAuthUser.some
          )
          .unsafeRunSync()

        result.results shouldBe {
          List(
            (publicDataset, publicProject).toDatasetSearchResult(projectsCount = 1),
            (privateDatasetWithAccess, privateProjectWithAccess).toDatasetSearchResult(projectsCount = 1)
          ) ::: List(
            (internalDatasetWithoutAccess, internalProjectWithoutAccess),
            (internalDatasetWithAccess, internalProjectWithAccess)
          ).toDatasetSearchResult(matchIdFrom = result.results, projectsCount = 2).toList
        }.sortBy(_.title)

        result.pagingInfo.total shouldBe Total(3)
      }
  }

  private trait TestCase {
    private implicit val logger:       TestLogger[IO]              = TestLogger[IO]()
    private implicit val timeRecorder: SparqlQueryTimeRecorder[IO] = TestSparqlQueryTimeRecorder[IO].unsafeRunSync()
    val datasetsFinder =
      new DatasetsFinderImpl[IO](projectsDSConnectionInfo, new CreatorsFinderImpl[IO](projectsDSConnectionInfo))
  }

  private lazy val publicProjectEntities: Gen[RenkuProject.WithoutParent] = renkuProjectEntities(visibilityPublic)

  implicit class PersonOps(person: Person) {
    lazy val toAuthUser: AuthUser = AuthUser(person.maybeGitLabId.get, userAccessTokens.generateOne)
  }

  private implicit class DatasetAndProjectOps2(datasetAndProject: (Dataset[Dataset.Provenance], RenkuProject)) {

    private lazy val (dataset, project) = datasetAndProject

    def toDatasetSearchResult(projectsCount: Int): DatasetSearchResult = DatasetSearchResult(
      dataset.identification.identifier,
      dataset.identification.title,
      dataset.identification.name,
      dataset.additionalInfo.maybeDescription,
      dataset.provenance.creators.map(_.to[DatasetCreator]).toList.sortBy(_.name),
      dataset.provenance.date,
      ExemplarProject(project.resourceId, project.slug),
      ProjectsCount(projectsCount),
      dataset.additionalInfo.keywords.sorted,
      dataset.additionalInfo.images
    )
  }

  private implicit class DatasetOps(dataset: Dataset[Dataset.Provenance]) {

    def toDatasetSearchResult(projectsCount:    Int,
                              results:          List[DatasetSearchResult],
                              matchProjectFrom: List[RenkuProject]
    ): DatasetSearchResult = {
      val matchingResult =
        results
          .find(_.id == dataset.identification.identifier)
          .getOrElse(fail("Cannot find matching DS in the results"))

      DatasetSearchResult(
        dataset.identification.identifier,
        dataset.identification.title,
        dataset.identification.name,
        dataset.additionalInfo.maybeDescription,
        dataset.provenance.creators.map(_.to[DatasetCreator]).toList,
        dataset.provenance.date,
        matchProjectFrom
          .find(_.slug == matchingResult.exemplarProject.slug)
          .map(project => ExemplarProject(project.resourceId, project.slug))
          .getOrElse(fail("Cannot find matching exemplar project slug in the results")),
        ProjectsCount(projectsCount),
        dataset.additionalInfo.keywords.sorted,
        dataset.additionalInfo.images
      )
    }
  }

  private implicit class ProjectsListOps(datasetsAndProjects: List[(Dataset[Dataset.Provenance], RenkuProject)]) {

    def toDatasetSearchResult(matchIdFrom:      List[DatasetSearchResult],
                              projectsCount:    Int,
                              matchProjectFrom: List[RenkuProject] = datasetsAndProjects.map(_._2)
    ): Option[DatasetSearchResult] =
      datasetsAndProjects
        .find { case (dataset, _) => matchIdFrom.exists(_.id == dataset.identifier) }
        .map { case (ds, _) => ds.toDatasetSearchResult(projectsCount, matchIdFrom, matchProjectFrom) }
  }
}
