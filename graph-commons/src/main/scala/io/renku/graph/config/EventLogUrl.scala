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

package io.renku.graph.config

import cats.MonadThrow
import com.typesafe.config.{Config, ConfigFactory}
import io.renku.config.ConfigLoader.{find, urlTinyTypeReader}
import io.renku.tinytypes.TinyTypeFactory
import io.renku.tinytypes.constraints.{Url, UrlOps}
import pureconfig.ConfigReader

final class EventLogUrl private (val value: String) extends AnyVal with EventConsumerUrl
object EventLogUrl
    extends TinyTypeFactory[EventLogUrl](new EventLogUrl(_))
    with Url[EventLogUrl]
    with UrlOps[EventLogUrl]
    with EventConsumerUrlFactory {

  type T = EventLogUrl

  implicit val eventLogUrlOps: EventLogUrl.type = this

  private implicit val urlReader: ConfigReader[EventLogUrl] = urlTinyTypeReader(EventLogUrl)

  override def apply[F[_]: MonadThrow](config: Config = ConfigFactory.load): F[EventLogUrl] =
    find[F, EventLogUrl]("services.event-log.url", config)
}
