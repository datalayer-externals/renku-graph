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

package io.renku.triplesgenerator.events.consumers.tsmigrationrequest.migrations.reprovisioning

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.graph.model.RenkuUrl
import io.renku.graph.model.Schemas.renku
import io.renku.graph.model.versions.RenkuVersionPair
import io.renku.triplesstore._
import io.renku.triplesstore.SparqlQuery.Prefixes
import org.typelevel.log4cats.Logger

trait RenkuVersionPairFinder[F[_]] {
  def find(): F[Option[RenkuVersionPair]]
}

private class RenkuVersionPairFinderImpl[F[_]: Async: Logger: SparqlQueryTimeRecorder](
    storeConfig: MigrationsConnectionConfig
)(implicit renkuUrl: RenkuUrl)
    extends TSClientImpl[F](storeConfig)
    with RenkuVersionPairFinder[F] {

  override def find(): F[Option[RenkuVersionPair]] = queryExpecting[List[RenkuVersionPair]] {
    SparqlQuery.of(
      name = "re-provisioning - version pair find",
      Prefixes of renku -> "renku",
      s"""|SELECT DISTINCT ?schemaVersion ?cliVersion 
          |WHERE {
          |  ?entityId a renku:VersionPair;
          |            renku:schemaVersion ?schemaVersion;
          |            renku:cliVersion ?cliVersion.
          |}
          |""".stripMargin
    )
  } >>= {
    case Nil         => Option.empty[RenkuVersionPair].pure[F]
    case head :: Nil => head.some.pure[F]
    case versionPairs =>
      new IllegalStateException(s"Too many Version pairs found: $versionPairs")
        .raiseError[F, Option[RenkuVersionPair]]
  }
}

private object RenkuVersionPairFinder {
  def apply[F[_]: Async: Logger: SparqlQueryTimeRecorder](
      storeConfig: MigrationsConnectionConfig
  )(implicit renkuUrl: RenkuUrl): F[RenkuVersionPairFinderImpl[F]] = MonadThrow[F].catchNonFatal {
    new RenkuVersionPairFinderImpl[F](storeConfig)
  }
}
