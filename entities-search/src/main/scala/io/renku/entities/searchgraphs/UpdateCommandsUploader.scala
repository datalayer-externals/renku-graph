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

package io.renku.entities.searchgraphs

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._
import io.renku.triplesstore.{ProjectsConnectionConfig, SparqlQueryTimeRecorder, TSClient}
import org.typelevel.log4cats.Logger

private trait UpdateCommandsUploader[F[_]] {
  def upload(commands: List[UpdateCommand]): F[Unit]
}

private object UpdateCommandsUploader {
  def apply[F[_]: Async: Logger: SparqlQueryTimeRecorder](
      connectionConfig: ProjectsConnectionConfig
  ): UpdateCommandsUploader[F] =
    new UpdateCommandsUploaderImpl[F](TSClient[F](connectionConfig))
}

private class UpdateCommandsUploaderImpl[F[_]: MonadThrow](tsClient: TSClient[F]) extends UpdateCommandsUploader[F] {

  import eu.timepit.refined.auto._
  import io.renku.triplesstore.SparqlQuery
  import io.renku.triplesstore.client.syntax._
  import tsClient.updateWithNoResult

  override def upload(commands: List[UpdateCommand]): F[Unit] =
    commands
      .groupBy(commandType)
      .flatMap(toSparqlQueries)
      .toList
      .map(updateWithNoResult)
      .sequence
      .void

  private sealed trait CommandType extends Product with Serializable
  private object CommandType {
    final case object Insert extends CommandType
    final case object Delete extends CommandType
    final case object Query  extends CommandType
  }

  private lazy val commandType: UpdateCommand => CommandType = {
    case _: UpdateCommand.Insert => CommandType.Insert
    case _: UpdateCommand.Delete => CommandType.Delete
    case _: UpdateCommand.Query  => CommandType.Query
  }

  private lazy val toSparqlQueries: ((CommandType, List[UpdateCommand])) => List[SparqlQuery] = {
    case (CommandType.Insert, cmds) =>
      List(
        SparqlQuery.of(
          "search info inserts",
          sparql"INSERT DATA {\n${cmds.map { case UpdateCommand.Insert(quad) => quad.asSparql }.combineAll}\n}"
        )
      )
    case (CommandType.Delete, cmds) =>
      List(
        SparqlQuery.of(
          "search info deletes",
          sparql"DELETE DATA {\n${cmds.map { case UpdateCommand.Delete(quad) => quad.asSparql }.combineAll}\n}"
        )
      )
    case (CommandType.Query, cmds) =>
      cmds.map { case UpdateCommand.Query(query) => query }
  }
}
