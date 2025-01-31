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

package io.renku.graph.model

import cats.syntax.all._
import io.renku.graph.model.views.{AnyResourceRenderer, EntityIdJsonLDOps, TinyTypeJsonLDOps}
import io.renku.tinytypes.constraints.{BoundedInstant, Url}
import io.renku.tinytypes.{InstantTinyType, StringTinyType, TinyTypeFactory}

import java.time.Instant

object activities {

  class ResourceId private (val value: String) extends AnyVal with StringTinyType
  implicit object ResourceId
      extends TinyTypeFactory[ResourceId](new ResourceId(_))
      with Url[ResourceId]
      with EntityIdJsonLDOps[ResourceId]
      with AnyResourceRenderer[ResourceId]

  final class StartTime private (val value: Instant) extends AnyVal with InstantTinyType
  implicit object StartTime
      extends TinyTypeFactory[StartTime](new StartTime(_))
      with BoundedInstant[StartTime]
      with TinyTypeJsonLDOps[StartTime] {
    import java.time.temporal.ChronoUnit.HOURS
    protected[this] override def maybeMax: Option[Instant] = instantNow.plus(24, HOURS).some
  }

  final class EndTime private (val value: Instant) extends AnyVal with InstantTinyType
  implicit object EndTime
      extends TinyTypeFactory[EndTime](new EndTime(_))
      with BoundedInstant[EndTime]
      with TinyTypeJsonLDOps[EndTime] {
    import java.time.temporal.ChronoUnit.HOURS
    protected[this] override def maybeMax: Option[Instant] = instantNow.plus(24, HOURS).some
  }
}
