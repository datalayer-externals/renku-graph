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

package io.renku.tinytypes.constraints

import io.renku.tinytypes.{Constraints, InstantTinyType}

import java.time.Instant

trait BoundedInstant[TT <: InstantTinyType] extends Constraints[TT] {
  protected[this] def maybeMin:   Option[Instant] = None
  protected[this] def maybeMax:   Option[Instant] = None
  protected[this] def instantNow: Instant         = Instant.now()

  addConstraint(
    check = v => maybeMin.forall(min => v.compareTo(min) >= 0) && maybeMax.forall(max => v.compareTo(max) <= 0),
    message = (_: Instant) => {
      val messageParts = List(
        maybeMin.map(v => s">= $v"),
        maybeMax.map(v => s"<= $v")
      ).flatten.mkString(" and ")
      s"$typeName has to be $messageParts"
    }
  )
}
