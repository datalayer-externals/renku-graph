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

package io.renku.triplesgenerator.tsprovisioning.transformation.namedgraphs.persons

import cats.MonadThrow
import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.graph.config.{GitLabUrlLoader, RenkuUrlLoader}
import io.renku.graph.model.entities.{EntityFunctions, Person, Project}
import io.renku.graph.model.{GitLabApiUrl, RenkuUrl}
import io.renku.triplesgenerator.errors.{ProcessingRecoverableError, RecoverableErrorsRecovery}
import io.renku.triplesgenerator.tsprovisioning.TransformationStep.{Queries, Transformation}
import io.renku.triplesgenerator.tsprovisioning.{ProjectFunctions, TransformationStep}
import io.renku.triplesstore.SparqlQueryTimeRecorder
import org.typelevel.log4cats.Logger

private[transformation] trait PersonTransformer[F[_]] {
  def createTransformationStep: TransformationStep[F]
}

private class PersonTransformerImpl[F[_]: MonadThrow](
    kgPersonFinder:            KGPersonFinder[F],
    personMerger:              PersonMerger,
    updatesCreator:            UpdatesCreator,
    projectFunctions:          ProjectFunctions,
    recoverableErrorsRecovery: RecoverableErrorsRecovery = RecoverableErrorsRecovery
)(implicit renkuUrl: RenkuUrl, glApiUrl: GitLabApiUrl)
    extends PersonTransformer[F] {

  import personMerger._
  import projectFunctions._
  import recoverableErrorsRecovery._
  import updatesCreator._

  override def createTransformationStep: TransformationStep[F] =
    TransformationStep("Person Details Updates", createTransformation)

  private def createTransformation: Transformation[F] = project =>
    EitherT {
      EntityFunctions[Project]
        .findAllPersons(project)
        .foldLeft((project -> Queries.empty).pure[F]) { (previousResultsF, person) =>
          updateProjectAndPreDataQueries(previousResultsF, person)
        }
        .map(_.asRight[ProcessingRecoverableError])
        .recoverWith(maybeRecoverableError("Problem finding person details in KG"))
    }

  private lazy val updateProjectAndPreDataQueries: (F[(Project, Queries)], Person) => F[(Project, Queries)] = {
    case (previousResultsF, person) =>
      for {
        previousResults   <- previousResultsF
        maybeKGPerson     <- kgPersonFinder find person
        maybeMergedPerson <- maybeKGPerson.map(merge(person, _)).sequence
      } yield (maybeKGPerson, maybeMergedPerson)
        .mapN { (kgPerson, mergedPerson) =>
          val updatedProject = update(person, mergedPerson)(previousResults._1)
          val preQueries     = preparePreDataUpdates(kgPerson, mergedPerson)
          val postQueries    = preparePostDataUpdates(mergedPerson)
          (updatedProject, previousResults._2 |+| Queries(preQueries, postQueries))
        }
        .getOrElse(previousResults)
  }
}

private[transformation] object PersonTransformer {

  def apply[F[_]: Async: Logger: SparqlQueryTimeRecorder]: F[PersonTransformer[F]] = for {
    kgPersonFinder                    <- KGPersonFinder[F]
    implicit0(renkuUrl: RenkuUrl)     <- RenkuUrlLoader[F]()
    implicit0(glApiUrl: GitLabApiUrl) <- GitLabUrlLoader[F]().map(_.apiV4)
  } yield new PersonTransformerImpl[F](kgPersonFinder, PersonMerger, UpdatesCreator, ProjectFunctions)
}
