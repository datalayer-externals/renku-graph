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

package io.renku.eventlog.init

import cats.data.Kleisli
import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.renku.eventlog.EventLogDB.SessionResource
import org.typelevel.log4cats.Logger
import skunk._
import skunk.implicits._

private trait ProjectPathRemover[F[_]] extends DbMigrator[F]

private object ProjectPathRemover {
  def apply[F[_]: MonadCancelThrow: Logger: SessionResource]: ProjectPathRemover[F] = new ProjectPathRemoverImpl[F]
}

private class ProjectPathRemoverImpl[F[_]: MonadCancelThrow: Logger: SessionResource] extends ProjectPathRemover[F] {
  import MigratorTools._

  override def run: F[Unit] = SessionResource[F].useK {
    whenTableExists("event")(
      Kleisli.liftF(Logger[F] info "'project_path' column dropping skipped"),
      otherwise = checkColumnExists("event_log", "project_path") >>= {
        case false => Kleisli.liftF[F, Session[F], Unit](Logger[F] info "'project_path' column already removed")
        case true  => removeColumn()
      }
    )
  }

  private def removeColumn(): Kleisli[F, Session[F], Unit] = for {
    _ <- execute(sql"ALTER TABLE event_log DROP COLUMN IF EXISTS project_path".command)
    _ <- Kleisli.liftF(Logger[F] info "'project_path' column removed")
  } yield ()
}
