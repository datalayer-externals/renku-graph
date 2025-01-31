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

package io.renku.entities

import io.renku.triplesstore.client.model.TripleObject
import io.renku.triplesstore.client.syntax._

package object searchgraphs {
  val concatSeparator: Char = ';'

  private[searchgraphs] def maybeTripleObject[A](values: List[A], toValue: A => String): Option[TripleObject] =
    values match {
      case Nil => Option.empty[TripleObject]
      case vls =>
        Option {
          vls.tail
            .foldLeft(new StringBuilder(toValue(vls.head)))((acc, v) => acc.append(concatSeparator).append(toValue(v)))
            .result()
            .asTripleObject
        }
    }
}
