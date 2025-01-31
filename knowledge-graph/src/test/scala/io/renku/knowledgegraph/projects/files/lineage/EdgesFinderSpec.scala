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

package io.renku.knowledgegraph.projects.files.lineage

import cats.effect.IO
import cats.syntax.all._
import io.renku.entities.searchgraphs.SearchInfoDatasets
import io.renku.generators.CommonGraphGenerators.authUsers
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.fixed
import io.renku.graph.model.GraphModelGenerators.projectSlugs
import io.renku.graph.model.projects.Visibility
import io.renku.graph.model.testentities.StepPlanCommandParameter.{CommandInput, CommandOutput}
import io.renku.graph.model.testentities._
import io.renku.graph.model.testentities.generators.EntitiesGenerators.ProjectBasedGenFactory
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Warn
import io.renku.jsonld.syntax._
import io.renku.logging.TestExecutionTimeRecorder
import io.renku.stubbing.ExternalServiceStubbing
import io.renku.testtools.IOSpec
import io.renku.triplesstore.{InMemoryJenaForSpec, ProjectsDataset, SparqlQueryTimeRecorder}
import model._
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.log4cats.Logger

class EdgesFinderSpec
    extends AnyWordSpec
    with should.Matchers
    with InMemoryJenaForSpec
    with ProjectsDataset
    with SearchInfoDatasets
    with ExternalServiceStubbing
    with IOSpec {

  implicit override val ioLogger: Logger[IO] = TestLogger[IO]()

  "findEdges" should {

    "return all the edges of the given project " +
      "case when the user is not authenticated and the project is public" in new TestCase {

        val exemplarData = LineageExemplarData(renkuProjectEntities(visibilityPublic).generateOne)

        import exemplarData._

        provisionTestProject(project).unsafeRunSync()

        edgesFinder
          .findEdges(project.slug, maybeUser = None)
          .unsafeRunSync() shouldBe Map(
          ExecutionInfo(activity1.asEntityId.show, RunDate(activity1.startTime.value)) -> (
            Set(`zhbikes folder`.toNodeLocation, `clean_data entity`.toNodeLocation),
            Set(`bikesparquet entity`.toNodeLocation)
          ),
          ExecutionInfo(activity2.asEntityId.show, RunDate(activity2.startTime.value)) -> (
            Set(`plot_data entity`.toNodeLocation, `bikesparquet entity`.toNodeLocation),
            Set(`grid_plot entity`.toNodeLocation, `cumulative entity`.toNodeLocation)
          ),
          ExecutionInfo(activity3.asEntityId.show, RunDate(activity3.startTime.value)) -> (
            Set(`zhbikes folder`.toNodeLocation, `clean_data entity`.toNodeLocation),
            Set(`bikesparquet entity`.toNodeLocation)
          ),
          ExecutionInfo(activity4.asEntityId.show, RunDate(activity4.startTime.value)) -> (
            Set(`plot_data entity`.toNodeLocation, `bikesparquet entity`.toNodeLocation),
            Set(`grid_plot entity`.toNodeLocation, `cumulative entity`.toNodeLocation)
          )
        )

        logger.logged(
          Warn(s"lineage - edges finished${executionTimeRecorder.executionTimeInfo}")
        )
      }

    /** in1 in2 in3 in2 
     *    \ /     \ / 
     *    plan   plan
     *     |     |
     *    out1  out2
     */
    "return all the edges including executions with overridden inputs/outputs" in new TestCase {

      val project = renkuProjectEntities(visibilityPublic).generateOne

      val in1  = entityLocations.generateOne
      val in2  = entityLocations.generateOne
      val out1 = entityLocations.generateOne
      val plan = stepPlanEntities(CommandInput.fromLocation(in1),
                                  CommandInput.fromLocation(in2),
                                  CommandOutput.fromLocation(out1)
      )(planCommands)(project.dateCreated).generateOne

      val activity1 = executionPlanners(ProjectBasedGenFactory.pure(plan), project).generateOne
        .planInputParameterValuesFromChecksum(in1 -> entityChecksums.generateOne, in2 -> entityChecksums.generateOne)
        .buildProvenanceUnsafe()

      val in3  = entityLocations.generateOne
      val out2 = entityLocations.generateOne
      val activity2 = executionPlanners(ProjectBasedGenFactory.pure(plan), project).generateOne
        .planInputParameterOverrides(in1 -> Entity.InputEntity(in3, entityChecksums.generateOne))
        .planInputParameterValuesFromChecksum(in2 -> entityChecksums.generateOne)
        .planOutputParameterOverrides(out1 -> out2)
        .buildProvenanceUnsafe()

      provisionTestProject(project.addActivities(activity1, activity2)).unsafeRunSync()

      edgesFinder
        .findEdges(project.slug, maybeUser = None)
        .unsafeRunSync() shouldBe Map(
        ExecutionInfo(activity1.asEntityId.show, RunDate(activity1.startTime.value)) -> (
          Set(Node.Location(in1.value), Node.Location(in2.value)),
          Set(Node.Location(out1.value))
        ),
        ExecutionInfo(activity2.asEntityId.show, RunDate(activity2.startTime.value)) -> (
          Set(Node.Location(in3.value), Node.Location(in2.value)),
          Set(Node.Location(out2.value))
        )
      )
    }

    "return None if there's no lineage for the project " +
      "case when the user is not authenticated and the project is public" in new TestCase {
        edgesFinder
          .findEdges(projectSlugs.generateOne, maybeUser = None)
          .unsafeRunSync() shouldBe empty
      }

    Visibility.Internal :: Visibility.Private :: Nil foreach { visibility =>
      s"return None if the project is $visibility " +
        "case when the user is not authenticated" in new TestCase {
          val exemplarData = LineageExemplarData(renkuProjectEntities(fixed(visibility)).generateOne)

          provisionTestProject(exemplarData.project).unsafeRunSync()

          edgesFinder
            .findEdges(projectSlugs.generateOne, authUsers.generateOption)
            .unsafeRunSync() shouldBe empty

          logger.logged(Warn(s"lineage - edges finished${executionTimeRecorder.executionTimeInfo}"))
        }
    }

    "return None if the project is private " +
      "case when the user is authenticated but he's not the project member" in new TestCase {
        val exemplarData = LineageExemplarData(renkuProjectEntities(fixed(Visibility.Private)).generateOne)

        provisionTestProject(exemplarData.project).unsafeRunSync()

        edgesFinder
          .findEdges(projectSlugs.generateOne, authUsers.generateSome)
          .unsafeRunSync() shouldBe empty

        logger.logged(Warn(s"lineage - edges finished${executionTimeRecorder.executionTimeInfo}"))
      }

    Visibility.Internal :: Visibility.Private :: Nil foreach { visibility =>
      s"return all the edges of the $visibility project " +
        "case when the user is authenticated and he's a member of the project" in new TestCase {
          val authUser = authUsers.generateOne

          val exemplarData = LineageExemplarData(
            renkuProjectEntities(fixed(visibility)).generateOne.copy(
              members = Set(memberPersonGitLabIdLens.replace(Some(authUser.id))(projectMemberEntities().generateOne))
            )
          )

          import exemplarData._

          provisionTestProject(project).unsafeRunSync()

          edgesFinder
            .findEdges(project.slug, Some(authUser))
            .unsafeRunSync() shouldBe Map(
            ExecutionInfo(activity1.asEntityId.show, RunDate(activity1.startTime.value)) -> (
              Set(`zhbikes folder`.toNodeLocation, `clean_data entity`.toNodeLocation),
              Set(`bikesparquet entity`.toNodeLocation)
            ),
            ExecutionInfo(activity2.asEntityId.show, RunDate(activity2.startTime.value)) -> (
              Set(`plot_data entity`.toNodeLocation, `bikesparquet entity`.toNodeLocation),
              Set(`grid_plot entity`.toNodeLocation, `cumulative entity`.toNodeLocation)
            ),
            ExecutionInfo(activity3.asEntityId.show, RunDate(activity3.startTime.value)) -> (
              Set(`zhbikes folder`.toNodeLocation, `clean_data entity`.toNodeLocation),
              Set(`bikesparquet entity`.toNodeLocation)
            ),
            ExecutionInfo(activity4.asEntityId.show, RunDate(activity4.startTime.value)) -> (
              Set(`plot_data entity`.toNodeLocation, `bikesparquet entity`.toNodeLocation),
              Set(`grid_plot entity`.toNodeLocation, `cumulative entity`.toNodeLocation)
            )
          )
        }
    }

    "return all the edges of the internal project " +
      "case when the user is authenticated and he's not a member of the project" in new TestCase {
        val authUser = authUsers.generateOne

        val exemplarData = LineageExemplarData(renkuProjectEntities(fixed(Visibility.Internal)).generateOne)

        import exemplarData._

        provisionTestProject(project).unsafeRunSync()

        edgesFinder
          .findEdges(project.slug, Some(authUser))
          .unsafeRunSync() shouldBe Map(
          ExecutionInfo(activity1.asEntityId.show, RunDate(activity1.startTime.value)) -> (
            Set(`zhbikes folder`.toNodeLocation, `clean_data entity`.toNodeLocation),
            Set(`bikesparquet entity`.toNodeLocation)
          ),
          ExecutionInfo(activity2.asEntityId.show, RunDate(activity2.startTime.value)) -> (
            Set(`plot_data entity`.toNodeLocation, `bikesparquet entity`.toNodeLocation),
            Set(`grid_plot entity`.toNodeLocation, `cumulative entity`.toNodeLocation)
          ),
          ExecutionInfo(activity3.asEntityId.show, RunDate(activity3.startTime.value)) -> (
            Set(`zhbikes folder`.toNodeLocation, `clean_data entity`.toNodeLocation),
            Set(`bikesparquet entity`.toNodeLocation)
          ),
          ExecutionInfo(activity4.asEntityId.show, RunDate(activity4.startTime.value)) -> (
            Set(`plot_data entity`.toNodeLocation, `bikesparquet entity`.toNodeLocation),
            Set(`grid_plot entity`.toNodeLocation, `cumulative entity`.toNodeLocation)
          )
        )
      }

    "return no the edges of the internal project " +
      "case when the user is anonymous" in new TestCase {
        val authUser = authUsers.generateOne

        val exemplarData = LineageExemplarData(renkuProjectEntities(fixed(Visibility.Internal)).generateOne)

        import exemplarData._

        provisionTestProject(project).unsafeRunSync()

        edgesFinder
          .findEdges(project.slug, None)
          .unsafeRunSync() shouldBe Map()
      }
  }

  private trait TestCase {
    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    val executionTimeRecorder = TestExecutionTimeRecorder[IO]()
    private implicit val timeRecorder: SparqlQueryTimeRecorder[IO] =
      new SparqlQueryTimeRecorder[IO](executionTimeRecorder)
    val edgesFinder = new EdgesFinderImpl[IO](projectsDSConnectionInfo, renkuUrl)
  }

  private implicit class NodeDefOps(nodeDef: NodeDef) {
    lazy val toNodeLocation: Node.Location = Node.Location(nodeDef.location)
  }
}
