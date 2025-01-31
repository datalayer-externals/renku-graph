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

package io.renku.eventsqueue

import io.renku.events.CategoryName
import skunk.codec.all.{int2, varchar}
import skunk.{Decoder, Encoder}

object TypeSerializers extends TypeSerializers

trait TypeSerializers {

  val categoryNameDecoder: Decoder[CategoryName] = varchar.map(CategoryName.apply)
  val categoryNameEncoder: Encoder[CategoryName] = varchar.values.contramap(_.value)

  private[eventsqueue] val enqueueStatusDecoder: Decoder[EnqueueStatus] = int2.emap { v =>
    EnqueueStatus.all.find(_.dbValue == v).toRight(left = s"'$v' is not valid EnqueueStatus")
  }
  private[eventsqueue] val enqueueStatusEncoder: Encoder[EnqueueStatus] = int2.values.contramap(_.dbValue)
}
