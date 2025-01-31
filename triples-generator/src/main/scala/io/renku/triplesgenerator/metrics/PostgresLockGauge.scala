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

package io.renku.triplesgenerator.metrics

import cats.effect._
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.auto._
import io.renku.metrics.{LabeledGauge, MetricsRegistry, PositiveValuesLabeledGauge}

trait PostgresLockGauge[F[_]] extends LabeledGauge[F, PostgresLockGauge.Label]

object PostgresLockGauge {

  sealed trait Label
  object Label {
    case object CurrentLocks extends Label {
      override def toString = "current_lock_count"
    }
    case object Waiting extends Label {
      override def toString = "waiting_count"
    }
  }

  def apply[F[_]: Async: MetricsRegistry](dbId: String Refined NonEmpty): F[PostgresLockGauge[F]] =
    MetricsRegistry[F]
      .register(
        new PositiveValuesLabeledGauge[F, Label](
          name = dbId,
          help = s"Statistics for postgres locks",
          labelName = "postgres_lock_stats",
          resetDataFetch = () => Async[F].pure(Map.empty)
        ) with PostgresLockGauge[F]
      )
      .widen

}
