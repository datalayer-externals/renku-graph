/*
 * Copyright 2022 Swiss Data Science Center (SDSC)
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

package io.renku.tokenrepository.repository.creation

import cats.Id
import cats.effect.MonadCancelThrow
import io.renku.db.DbClient
import io.renku.graph.model.projects
import io.renku.tokenrepository.repository.ProjectsTokensDB.SessionResource
import io.renku.tokenrepository.repository.TokenRepositoryTypeSerializers
import io.renku.tokenrepository.repository.metrics.QueriesExecutionTimes

private trait PersistedPathFinder[F[_]] {
  def findPersistedProjectPath(projectId: projects.GitLabId): F[projects.Path]
}

private object PersistedPathFinder {
  def apply[F[_]: MonadCancelThrow: SessionResource: QueriesExecutionTimes]: PersistedPathFinder[F] =
    new PersistedPathFinderImpl[F]
}

private class PersistedPathFinderImpl[F[_]: MonadCancelThrow: SessionResource: QueriesExecutionTimes]
    extends DbClient[F](Some(QueriesExecutionTimes[F]))
    with PersistedPathFinder[F]
    with TokenRepositoryTypeSerializers {

  import io.renku.db.SqlStatement
  import skunk.implicits._

  override def findPersistedProjectPath(projectId: projects.GitLabId): F[projects.Path] =
    SessionResource[F].useK(measureExecutionTime(query(projectId)))

  private def query(projectId: projects.GitLabId) =
    SqlStatement
      .named("find path for token")
      .select[projects.GitLabId, projects.Path](
        sql"""SELECT project_path
              FROM projects_tokens
              WHERE project_id = $projectIdEncoder"""
          .query(projectPathDecoder)
      )
      .arguments(projectId)
      .build[Id](_.unique)
}
