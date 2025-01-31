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

import cats.data.Validated
import io.renku.tinytypes.{Constraints, TinyType, TinyTypeFactory}

import java.util.UUID.fromString

trait UUID[TT <: TinyType { type V = String }] extends Constraints[TT] with NonBlank[TT] {
  self: TinyTypeFactory[TT] =>

  private val validationRegexNoDashes: String = "[0-9a-f]{32}"

  addConstraint(
    check = v =>
      if (v contains "-") Validated.catchOnly[IllegalArgumentException](fromString(v)).isValid
      else v.matches(validationRegexNoDashes),
    message = (value: String) => s"'$value' is not a valid UUID value for $typeName"
  )
}
