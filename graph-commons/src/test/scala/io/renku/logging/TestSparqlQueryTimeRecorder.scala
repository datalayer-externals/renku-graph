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

package io.renku.logging

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Sync}
import io.renku.metrics.{MetricsRegistry, TestMetricsRegistry}
import io.renku.triplesstore.SparqlQueryTimeRecorder
import org.typelevel.log4cats.Logger

object TestSparqlQueryTimeRecorder {
  def apply[F[_]: Sync: Logger]: F[SparqlQueryTimeRecorder[F]] = {
    implicit val metricsRegistry: MetricsRegistry[F] = TestMetricsRegistry[F]
    SparqlQueryTimeRecorder.create[F]()
  }

  def createUnsafe(implicit logger: Logger[IO], IORuntime: IORuntime): SparqlQueryTimeRecorder[IO] =
    apply[IO].unsafeRunSync()
}
