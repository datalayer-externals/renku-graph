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

package io.renku.commiteventservice.events.consumers.common

import SynchronizationSummary._
import cats.data.StateT
import cats.kernel.Semigroup
import cats.syntax.all._
import cats.{Applicative, Show}

private[consumers] final case class SynchronizationSummary(private val summary: Map[SummaryKey, Int]) {

  def get(key: String): Int = summary.getOrElse(key, 0)

  def get(result: UpdateResult): Int = get(result.name)

  def updated(result: UpdateResult, newValue: Int): SynchronizationSummary =
    new SynchronizationSummary(summary.updated(result.name, newValue))

  def incrementCount(result: UpdateResult): SynchronizationSummary = {
    val key = result.name
    new SynchronizationSummary(summary.updated(key, get(key) + 1))
  }
}

private[consumers] object SynchronizationSummary {

  type SummaryKey            = String
  type SummaryState[F[_], A] = StateT[F, SynchronizationSummary, A]

  def apply(initialState: (SummaryKey, Int)*) = new SynchronizationSummary(initialState.toMap)

  def incrementCount[F[_]: Applicative](result: UpdateResult): SummaryState[F, Unit] = StateT { summaryMap =>
    (summaryMap.incrementCount(result), ()).pure[F]
  }

  implicit lazy val show: Show[SynchronizationSummary] = Show.show { summary =>
    summary.summary.toList
      .sortBy(_._1)
      .map { case (key, count) => s"$count ${key.toLowerCase}" }
      .mkString(", ")
  }

  implicit val semigroup: Semigroup[SynchronizationSummary] =
    (x: SynchronizationSummary, y: SynchronizationSummary) => new SynchronizationSummary(x.summary combine y.summary)
}
