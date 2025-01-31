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

package io.renku.eventlog.events.producers.tsmigrationrequest

import cats.syntax.all._
import io.renku.events.Generators.{subscriberIds, subscriberUrls}
import io.renku.generators.CommonGraphGenerators.serviceVersions
import io.renku.generators.Generators.Implicits._
import org.scalacheck.Gen

private object Generators {

  implicit val migrationSubscribers: Gen[MigrationSubscriber] = for {
    url     <- subscriberUrls
    id      <- subscriberIds
    version <- serviceVersions
  } yield MigrationSubscriber(url, id, version)

  val migrationRequestEvents: Gen[MigrationRequestEvent] =
    (subscriberUrls -> serviceVersions).mapN(MigrationRequestEvent(_, _))
}
