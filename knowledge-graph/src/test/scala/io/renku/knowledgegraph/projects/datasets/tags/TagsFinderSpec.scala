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

package io.renku.knowledgegraph.projects.datasets.tags

import Endpoint._
import cats.effect.IO
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.GraphModelGenerators.{datasetNames, projectSlugs}
import io.renku.graph.model.testentities._
import io.renku.http.rest.paging.model.Total
import io.renku.interpreters.TestLogger
import io.renku.logging.TestSparqlQueryTimeRecorder
import io.renku.testtools.IOSpec
import io.renku.triplesstore.{InMemoryJenaForSpec, ProjectsDataset, SparqlQueryTimeRecorder}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class TagsFinderSpec
    extends AnyWordSpec
    with should.Matchers
    with InMemoryJenaForSpec
    with ProjectsDataset
    with IOSpec {

  "findTags" should {

    "return all PublicationEvent objects linked to the Dataset family sharing the name" in new TestCase {

      val (original, modified, project) = {
        val projStage1 = renkuProjectEntities(visibilityPublic).generateOne
        val (original, projStage2) = projStage1.addDataset(
          datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(List(publicationEventFactory)))
        )

        val (modified, projStage3) = projStage2.addDataset(
          original.createModification().modify(_.replacePublicationEvents(List(publicationEventFactory)))
        )

        val (_, projStage4) = projStage3.addDataset(
          datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(List(publicationEventFactory)))
        )

        (original, modified, projStage4)
      }

      upload(to = projectsDataset, project)

      original.identification.name shouldBe modified.identification.name

      project.datasets.flatMap(_.publicationEvents).size shouldBe 3

      val response = finder.findTags(Criteria(project.slug, original.identification.name)).unsafeRunSync()

      response.results shouldBe List(original, modified)
        .flatMap(_.publicationEvents)
        .map(_.to[model.Tag])
        .sortBy(_.startDate)
        .reverse
      response.pagingInfo.total shouldBe Total(2)
    }

    "return PublicationEvent objects linked to the Dataset family sharing the name from a the given project" in new TestCase {

      val (ds, project) = renkuProjectEntities(visibilityPublic)
        .addDataset(
          datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(List(publicationEventFactory)))
        )
        .generateOne

      val dsPublicationEvent = ds.publicationEvents.head

      val (importedDs, projectWithImportedDs) = renkuProjectEntities(visibilityPublic)
        .importDataset(dsPublicationEvent)
        .generateOne

      upload(to = projectsDataset, project, projectWithImportedDs)

      project.datasets.flatMap(_.publicationEvents).size               shouldBe 1
      projectWithImportedDs.datasets.flatMap(_.publicationEvents).size shouldBe 1

      val response = finder.findTags(Criteria(project.slug, ds.identification.name)).unsafeRunSync()

      response.results shouldBe List(ds)
        .flatMap(_.publicationEvents)
        .map(_.to[model.Tag])
        .sortBy(_.startDate)
        .reverse
      response.pagingInfo.total shouldBe Total(1)

      finder
        .findTags(Criteria(projectWithImportedDs.slug, ds.identification.name))
        .unsafeRunSync()
        .results shouldBe List(importedDs)
        .flatMap(_.publicationEvents)
        .map(_.to[model.Tag])
        .sortBy(_.startDate)
        .reverse
    }
  }

  "return no PublicationEvent if any on the Dataset" in new TestCase {

    val (original, project) = renkuProjectEntities(visibilityPublic)
      .addDataset(datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(Nil)))
      .generateOne

    upload(to = projectsDataset, project)

    val response = finder.findTags(Criteria(project.slug, original.identification.name)).unsafeRunSync()

    response.results          shouldBe Nil
    response.pagingInfo.total shouldBe Total(0)
  }

  "return no PublicationEvent for non-existing Project" in new TestCase {

    val response = finder.findTags(Criteria(projectSlugs.generateOne, datasetNames.generateOne)).unsafeRunSync()

    response.results          shouldBe Nil
    response.pagingInfo.total shouldBe Total(0)
  }

  "not return PublicationEvent if on Dataset having matching name but on different Project" in new TestCase {

    val (original, project) = renkuProjectEntities(visibilityPublic)
      .addDataset(datasetEntities(provenanceInternal).modify(_.replacePublicationEvents(Nil)))
      .generateOne

    upload(to = projectsDataset, project)

    val response = finder.findTags(Criteria(projectSlugs.generateOne, original.identification.name)).unsafeRunSync()

    response.results          shouldBe Nil
    response.pagingInfo.total shouldBe Total(0)
  }

  private trait TestCase {
    private implicit val logger:       TestLogger[IO]              = TestLogger[IO]()
    private implicit val timeRecorder: SparqlQueryTimeRecorder[IO] = TestSparqlQueryTimeRecorder[IO].unsafeRunSync()
    val finder = new TagsFinderImpl[IO](projectsDSConnectionInfo)
  }

  private implicit lazy val toTag: PublicationEvent => model.Tag = event =>
    model.Tag(
      event.name,
      event.startDate,
      event.maybeDescription,
      event.dataset.identification.identifier
    )
}
