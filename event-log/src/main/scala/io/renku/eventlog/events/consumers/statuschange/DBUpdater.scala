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

package io.renku.eventlog.events.consumers.statuschange

import DBUpdater._
import cats.ApplicativeThrow
import cats.data.Kleisli
import io.renku.eventlog.EventLogDB.SessionResource
import io.renku.eventlog.api.events.StatusChangeEvent
import skunk.Session

private trait DBUpdater[F[_], E <: StatusChangeEvent] {
  def updateDB(event:   E): UpdateOp[F]
  def onRollback(event: E)(implicit sr: SessionResource[F]): RollbackOp[F]
}

private object DBUpdater {

  type UpdateOp[F[_]]   = Kleisli[F, Session[F], DBUpdateResults]
  type RollbackOp[F[_]] = PartialFunction[Throwable, F[DBUpdateResults]]

  object RollbackOp {
    def empty[F[_]: ApplicativeThrow]: RollbackOp[F] = PartialFunction.empty[Throwable, F[DBUpdateResults]]
  }
}
