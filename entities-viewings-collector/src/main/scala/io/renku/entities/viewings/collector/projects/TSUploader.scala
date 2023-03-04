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

package io.renku.entities.viewings.collector.projects

import cats.effect.Async
import cats.syntax.all._
import io.renku.triplesstore.{ProjectsConnectionConfig, SparqlQueryTimeRecorder, TSClient}
import org.typelevel.log4cats.Logger

private trait TSUploader[F[_]] {
  def uploadToTS(event: ProjectViewedEvent): F[Unit]
}

private object TSUploader {
  def apply[F[_]: Async: Logger: SparqlQueryTimeRecorder]: F[TSUploader[F]] =
    ProjectsConnectionConfig[F]().map(TSClient[F](_)).map(new TSUploaderImpl[F](_))
}

private class TSUploaderImpl[F[_]](tsClient: TSClient[F]) extends TSUploader[F] {
  override def uploadToTS(event: ProjectViewedEvent): F[Unit] = ???
}
