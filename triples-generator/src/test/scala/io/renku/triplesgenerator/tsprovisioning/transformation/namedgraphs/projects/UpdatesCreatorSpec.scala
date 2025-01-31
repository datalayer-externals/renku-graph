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

package io.renku.triplesgenerator.tsprovisioning.transformation.namedgraphs.projects

import TestDataTools._
import cats.effect.std.CountDownLatch
import cats.effect.{IO, Spawn}
import cats.syntax.all._
import com.softwaremill.diffx.Diff
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import eu.timepit.refined.auto._
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model
import io.renku.graph.model.Schemas.{prov, renku, schema}
import io.renku.graph.model._
import io.renku.graph.model.projects.DateCreated
import io.renku.graph.model.testentities.generators.EntitiesGenerators
import io.renku.jsonld.syntax._
import io.renku.testtools.CustomAsyncIOSpec
import io.renku.tinytypes.syntax.all._
import io.renku.triplesstore.SparqlQuery.Prefixes
import io.renku.triplesstore._
import io.renku.triplesstore.client.model.Quad
import io.renku.triplesstore.client.syntax._
import monocle.Lens
import org.scalacheck.Gen
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant
import scala.concurrent.duration._

class UpdatesCreatorSpec
    extends AsyncWordSpec
    with CustomAsyncIOSpec
    with should.Matchers
    with EntitiesGenerators
    with DiffShouldMatcher
    with MoreDiffInstances
    with InMemoryJenaForSpec
    with ProjectsDataset
    with TableDrivenPropertyChecks
    with ScalaCheckPropertyChecks {

  import UpdatesCreator._

  "postUpdates" should {

    "remove duplicate created dates" in {

      val dateCreated = DateCreated(Instant.parse("2022-12-09T13:45:13Z"))
      val project1 =
        projectCreatedLens.replace(dateCreated)(
          anyProjectEntities.generateOne.to[entities.Project]
        )
      val project2 = projectCreatedLens.modify(_ - 1.days).apply(project1)
      upload(to = projectsDataset, project1)
      upload(to = projectsDataset, project2)

      postUpdates(project1).runAll(projectsDataset) >>
        findProjects.asserting { projects =>
          projects.size                  shouldBe 1
          projects.head.maybeName        shouldBe project1.name.value.some
          projects.head.maybeDateCreated shouldBe project2.dateCreated.some
        }
    }

    "retain any single date" in {

      val dateCreated = DateCreated(Instant.parse("2022-12-09T13:45:13Z"))
      val project =
        projectCreatedLens.replace(dateCreated)(
          anyProjectEntities.generateOne.to[entities.Project]
        )
      upload(to = projectsDataset, project)

      postUpdates(project).runAll(projectsDataset) >>
        findProjects.asserting { projects =>
          projects.size                  shouldBe 1
          projects.head.maybeName        shouldBe project.name.value.some
          projects.head.maybeDateCreated shouldBe project.dateCreated.some
        }
    }

    "retain minimum date when delete concurrently" in {

      val dateCreated = DateCreated(Instant.parse("2022-12-09T13:45:13Z"))
      val project1 =
        projectCreatedLens.replace(dateCreated)(
          anyProjectEntities.generateOne.to[entities.Project]
        )
      val project2 = projectCreatedLens.modify(_ - 1.days).apply(project1)
      upload(to = projectsDataset, project1)
      upload(to = projectsDataset, project2)

      val wait  = CountDownLatch[IO](1).unsafeRunSync()
      val task1 = wait.await *> postUpdates(project1).runAll(projectsDataset)
      val task2 = wait.await *> postUpdates(project2).runAll(projectsDataset)
      val run =
        for {
          fib <- Spawn[IO].start(List(task1, task2).parSequence)
          _   <- wait.release
          _   <- fib.join
        } yield ()

      run >>
        findProjects.asserting { projects =>
          projects.size                  shouldBe 1
          projects.head.maybeName        shouldBe project1.name.value.some
          projects.head.maybeDateCreated shouldBe project2.dateCreated.some
        }
    }

    "don't confuse with datasets when removing dates" in {

      val (dataset, project) = anyRenkuProjectEntities
        .addDataset(datasetEntities(provenanceInternal))
        .generateOne
        .bimap(identity, _.to[entities.Project])
      upload(to = projectsDataset, project)

      postUpdates(project).runAll(projectsDataset) >>
        findProjects.asserting { projects =>
          projects.size                  shouldBe 1
          projects.head.maybeName        shouldBe project.name.value.some
          projects.head.maybeDateCreated shouldBe project.dateCreated.some
        } >>
        findDatasets.asserting { datasets =>
          datasets.size      shouldBe 1
          datasets.head.name shouldBe dataset.identification.title.value.some
        }
    }
  }

  "prepareUpdates" should {
    "not delete existing images if they did not change" in {

      val project = anyProjectEntities
        .suchThat(_.images.nonEmpty)
        .generateOne
        .to[entities.Project]

      upload(to = projectsDataset, project)

      prepareUpdates(project, toProjectMutableData(project)).runAll(on = projectsDataset) >>
        project.images.map { im =>
          findImage(im.resourceId).asserting(_ shouldBe im.some)
        }.sequence >>
        findProjects.asserting(
          _.flatMap(_.images) should contain theSameElementsAs project.images.map(_.resourceId.show)
        )
    }

    "generate queries which delete the project images when changed" in {

      val project = anyProjectEntities.suchThat(_.images.nonEmpty).generateOne.to[entities.Project]

      upload(to = projectsDataset, project)

      findProjects
        .asserting(_.flatMap(_.images) should contain theSameElementsAs project.images.map(_.resourceId.show)) >>
        prepareUpdates(project, toProjectMutableData(project).copy(images = Nil))
          .runAll(on = projectsDataset) >>
        project.images.map { im =>
          findImage(im.resourceId).asserting(_ shouldBe None)
        }.sequence >>
        findProjects.asserting(_.flatMap(_.images) shouldBe Set.empty)
    }

    "generate queries which delete the project name when changed" in {

      val project = anyProjectEntities.generateOne.to[entities.Project]

      upload(to = projectsDataset, project)

      prepareUpdates(project, toProjectMutableData(project).copy(name = projectNames.generateOne))
        .runAll(on = projectsDataset) >>
        findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeName = None)))
    }

    val projectWithParentScenarios = Table(
      "project" -> "type",
      renkuProjectWithParentEntities(anyVisibility).generateOne.to[entities.RenkuProject.WithParent] ->
        "renku project",
      nonRenkuProjectWithParentEntities(anyVisibility).generateOne.to[entities.NonRenkuProject.WithParent] ->
        "non-renku project"
    )

    forAll(projectWithParentScenarios) { case (project, projectType) =>
      s"generate queries which deletes the $projectType's derivedFrom when changed" in {

        upload(to = projectsDataset, project)

        val kgProjectInfo = toProjectMutableData(project).copy(maybeParentId = projectResourceIds.generateSome)

        prepareUpdates(project, kgProjectInfo).runAll(on = projectsDataset) >>
          findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeParentId = None)))
      }
    }

    val projectWithoutParentScenarios = Table(
      "project" -> "type",
      renkuProjectEntities(anyVisibility).generateOne.to[entities.RenkuProject.WithoutParent] ->
        "renku project",
      nonRenkuProjectEntities(anyVisibility).generateOne.to[entities.NonRenkuProject.WithoutParent] ->
        "non-renku project"
    )

    forAll(projectWithoutParentScenarios) { case (project, projectType) =>
      s"generate queries which deletes the $projectType's derivedFrom when removed" in {

        upload(to = projectsDataset, project)

        val parentId = projectResourceIds.generateOne
        insert(to = projectsDataset,
               Quad(GraphClass.Project.id(project.resourceId),
                    project.resourceId.asEntityId,
                    prov / "wasDerivedFrom",
                    parentId.asEntityId
               )
        )

        findProjects
          .asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeParentId = parentId.show.some))) >>
          prepareUpdates(project, toProjectMutableData(project).copy(maybeParentId = parentId.some))
            .runAll(on = projectsDataset) >>
          findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeParentId = None)))
      }
    }

    forAll(projectWithParentScenarios) { case (project, projectType) =>
      s"not generate queries which deletes the $projectType's derivedFrom when NOT changed" in {

        upload(to = projectsDataset, project)

        prepareUpdates(project, toProjectMutableData(project)).runAll(on = projectsDataset) >>
          findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project)))
      }
    }

    "generate queries which deletes the project visibility when changed" in {

      val project = anyProjectEntities.generateOne.to[entities.Project]
      val kgProjectInfo = toProjectMutableData(project)
        .copy(visibility = Gen.oneOf(projects.Visibility.all.filterNot(_ == project.visibility)).generateOne)

      upload(to = projectsDataset, project)

      prepareUpdates(project, kgProjectInfo).runAll(on = projectsDataset) >>
        findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeVisibility = None)))
    }

    forAll(anyProjectEntities.map(_.to[entities.Project])) { project =>
      s"generate queries which deletes the project description when changed - project ${project.name}" in {

        val kgProjectInfo = toProjectMutableData(project).copy(maybeDescription = projectDescriptions.generateSome)

        upload(to = projectsDataset, project)

        prepareUpdates(project, kgProjectInfo).runAll(on = projectsDataset) >>
          findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeDesc = None)))
      }
    }

    "generate queries which deletes the project keywords when changed" in {

      val project       = anyProjectEntities.generateOne.to[entities.Project]
      val kgProjectInfo = toProjectMutableData(project).copy(keywords = projectKeywords.generateSet(min = 1))

      upload(to = projectsDataset, project)

      prepareUpdates(project, kgProjectInfo).runAll(on = projectsDataset) >>
        findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(keywords = Set.empty)))
    }

    forAll(anyRenkuProjectEntities.map(_.to[entities.RenkuProject])) { project =>
      s"generate queries which deletes the project agent when changed - project ${project.name}" in {
        val kgProjectInfo =
          toProjectMutableData(project).copy(maybeAgent = GraphModelGenerators.cliVersions.generateSome)

        upload(to = projectsDataset, project)

        prepareUpdates(project, kgProjectInfo).runAll(on = projectsDataset) >>
          findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeAgent = None)))
      }
    }

    forAll(anyProjectEntities.map(_.to[entities.Project])) { project =>
      s"generate queries which deletes the project creator when changed - project ${project.name}" in {

        val kgProjectInfo = toProjectMutableData(project).copy(maybeCreatorId = personResourceIds.generateSome)

        upload(to = projectsDataset, project)

        prepareUpdates(project, kgProjectInfo).runAll(on = projectsDataset) >>
          findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeCreatorId = None)))
      }
    }

    "not generate queries when nothing changed" in {

      val project = anyProjectEntities.generateOne.to[entities.Project]

      upload(to = projectsDataset, project)

      prepareUpdates(project, toProjectMutableData(project)).runAll(on = projectsDataset) >>
        findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project)))
    }
  }

  "dateCreatedDeletion" should {

    "generate queries which delete the project dateCreated when changed" in {

      val project = anyProjectEntities.generateOne.to[entities.Project]

      upload(to = projectsDataset, project)

      dateCreatedDeletion(
        project,
        toProjectMutableData(project).copy(createdDates = projectCreatedDates().generateNonEmptyList())
      ).runAll(on = projectsDataset) >>
        findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeDateCreated = None)))
    }
  }

  "dateModifiedDeletion" should {

    "generate queries which delete the project dateModified when changed" in {

      val project = anyProjectEntities.generateOne.to[entities.Project]

      upload(to = projectsDataset, project)

      dateModifiedDeletion(
        project,
        toProjectMutableData(project)
          .copy(modifiedDates = projectModifiedDates(project.dateCreated.value).generateNonEmptyList().toList)
      ).runAll(on = projectsDataset) >>
        findProjects.asserting(_ shouldBe Set(CurrentProjectState.from(project).copy(maybeDateModified = None)))
    }
  }

  private case class CurrentProjectState(maybeName:         Option[String],
                                         maybeDateCreated:  Option[projects.DateCreated],
                                         maybeDateModified: Option[projects.DateModified],
                                         maybeParentId:     Option[String],
                                         maybeVisibility:   Option[String],
                                         maybeDesc:         Option[String],
                                         keywords:          Set[String],
                                         maybeAgent:        Option[String],
                                         maybeCreatorId:    Option[String],
                                         images:            Set[String]
  )

  private case class CurrentDatasetState(name: Option[String], dateCreated: Option[Instant])

  private object CurrentProjectState {
    def from(project: entities.Project): CurrentProjectState = CurrentProjectState(
      project.name.value.some,
      project.dateCreated.some,
      project.dateModified.some,
      findParent(project).map(_.value),
      project.visibility.value.some,
      project.maybeDescription.map(_.value),
      project.keywords.map(_.value),
      findAgent(project).map(_.value),
      project.maybeCreator.map(_.resourceId.value),
      project.images.map(_.resourceId.value).toSet
    )

    implicit val diff: Diff[CurrentProjectState] =
      Diff.derived[CurrentProjectState]
  }

  private def findDatasets: IO[Set[CurrentDatasetState]] =
    runSelect(
      projectsDataset,
      SparqlQuery.of(
        "fetch datasets",
        Prefixes.of(prov -> "prov", renku -> "renku", schema -> "schema"),
        s"""SELECT ?name ?dateCreated
           |WHERE {
           |  Graph ?g {
           |    ?id a schema:Dataset
           |    OPTIONAL { ?id schema:name ?name }
           |    OPTIONAL { ?id schema:dateCreated ?dateCreated }
           |  }
           |}
           |""".stripMargin
      )
    ).map(
      _.map(row =>
        CurrentDatasetState(
          name = row.get("name"),
          dateCreated = row.get("dateCreated").map(d => Instant.parse(d))
        )
      ).toSet
    )

  private def findProjects: IO[Set[CurrentProjectState]] = runSelect(
    on = projectsDataset,
    SparqlQuery.of(
      "fetch project data",
      Prefixes.of(prov -> "prov", renku -> "renku", schema -> "schema"),
      s"""|SELECT ?name ?dateCreated ?dateModified ?maybeParent ?visibility ?maybeDesc
          |  (GROUP_CONCAT(?keyword; separator=',') AS ?keywords) ?maybeAgent ?maybeCreatorId
          |  (GROUP_CONCAT(?imageId; separator=',') AS ?images)
          |WHERE {
          |  GRAPH ?id {
          |    ?id a schema:Project
          |    OPTIONAL { ?id schema:name ?name } 
          |    OPTIONAL { ?id schema:dateCreated ?dateCreated } 
          |    OPTIONAL { ?id schema:dateModified ?dateModified }
          |    OPTIONAL { ?id prov:wasDerivedFrom ?maybeParent }
          |    OPTIONAL { ?id renku:projectVisibility ?visibility } 
          |    OPTIONAL { ?id schema:description ?maybeDesc } 
          |    OPTIONAL { ?id schema:keywords ?keyword } 
          |    OPTIONAL { ?id schema:agent ?maybeAgent } 
          |    OPTIONAL { ?id schema:creator ?maybeCreatorId }
          |    OPTIONAL { ?id schema:image ?imageId }
          |  }
          |}
          |GROUP BY ?name ?dateCreated ?dateModified ?maybeParent ?visibility ?maybeDesc ?maybeAgent ?maybeCreatorId
          |""".stripMargin
    )
  ).map(
    _.map(row =>
      CurrentProjectState(
        maybeName = row.get("name"),
        maybeDateCreated = row.get("dateCreated").map(d => projects.DateCreated(Instant.parse(d))),
        maybeDateModified = row.get("dateModified").map(d => projects.DateModified(Instant.parse(d))),
        maybeParentId = row.get("maybeParent"),
        maybeVisibility = row.get("visibility"),
        maybeDesc = row.get("maybeDesc"),
        keywords = row.get("keywords").map(_.split(',').toList).sequence.flatten.toSet,
        maybeAgent = row.get("maybeAgent"),
        maybeCreatorId = row.get("maybeCreatorId"),
        images = row.get("images").map(_.split(',').toSet).getOrElse(Set.empty)
      )
    ).toSet
  )

  private def findImage(id: model.images.ImageResourceId): IO[Option[model.images.Image]] = runSelect(
    on = projectsDataset,
    SparqlQuery.of(
      "fetch image",
      Prefixes of schema -> "schema",
      s"""|SELECT ?imageId ?uri ?position
          |WHERE {
          |  GRAPH ?id {
          |    BIND (${id.asEntityId.asSparql.sparql} AS ?imageId)
          |    ?imageId schema:contentUrl ?uri;
          |             schema:position ?position.
          |  }
          |}
          |""".stripMargin
    )
  ).map(
    _.map(row =>
      model.images.Image(
        row.get("imageId").map(model.images.ImageResourceId.apply).getOrElse(fail("No image id")),
        row.get("uri").map(model.images.ImageUri.apply).getOrElse(fail("No image uri")),
        row.get("position").map(p => model.images.ImagePosition(p.toInt)).getOrElse(fail("No image position"))
      )
    ).headOption
  )

  private def projectCreatedLens: Lens[entities.Project, DateCreated] =
    Lens[entities.Project, DateCreated](_.dateCreated) { created =>
      {
        case p: entities.NonRenkuProject.WithParent    => p.copy(dateCreated = created)
        case p: entities.NonRenkuProject.WithoutParent => p.copy(dateCreated = created)
        case p: entities.RenkuProject.WithParent       => p.copy(dateCreated = created)
        case p: entities.RenkuProject.WithoutParent    => p.copy(dateCreated = created)
      }
    }
}
