/*
 * Copyright 2021 Swiss Data Science Center (SDSC)
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

package ch.datascience.tokenrepository.repository.deletion

import cats.Monad
import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.all._
import ch.datascience.db.{DbClient, SessionResource, SqlQuery}
import ch.datascience.graph.model.projects.Id
import ch.datascience.metrics.LabeledHistogram
import ch.datascience.tokenrepository.repository.{ProjectsTokensDB, TokenRepositoryTypeSerializers}
import eu.timepit.refined.auto._
import skunk._
import skunk.data.Completion
import skunk.implicits._

private[repository] class TokenRemover[Interpretation[_]: Async: Monad](
    sessionResource:  SessionResource[Interpretation, ProjectsTokensDB],
    queriesExecTimes: LabeledHistogram[Interpretation, SqlQuery.Name]
) extends DbClient[Interpretation](Some(queriesExecTimes))
    with TokenRepositoryTypeSerializers {

  def delete(projectId: Id): Interpretation[Unit] = sessionResource.useK {
    measureExecutionTime {
      val command: Command[Id] =
        sql"""delete from projects_tokens
              where project_id = $projectIdEncoder
        """.command
      SqlQuery[Interpretation, Unit](
        Kleisli(_.prepare(command).use(_.execute(projectId).flatMap(failIfMultiUpdate(projectId)))),
        name = "remove token"
      )
    }
  }

  private def failIfMultiUpdate(projectId: Id): Completion => Interpretation[Unit] = {
    case Completion.Delete(0 | 1) => ().pure[Interpretation]
    case Completion.Delete(n) =>
      new RuntimeException(s"Deleting token for a projectId: $projectId removed $n records")
        .raiseError[Interpretation, Unit]
    case completion =>
      new RuntimeException(s"Deleting token for a projectId: $projectId failed with completion code $completion")
        .raiseError[Interpretation, Unit]
  }
}
