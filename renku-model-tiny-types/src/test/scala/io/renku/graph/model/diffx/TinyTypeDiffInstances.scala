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

package io.renku.graph.model.diffx

import cats.data.NonEmptyList
import com.softwaremill.diffx.Diff
import io.circe.Json
import io.renku.jsonld.JsonLD
import io.renku.tinytypes._

trait TinyTypeDiffInstances {

  implicit def nonEmptyListDiff[A: Diff]: Diff[NonEmptyList[A]] =
    Diff.diffForSeq[List, A].contramap(_.toList)

  implicit def stringTinyTypeDiff[A <: StringTinyType]: Diff[A] =
    Diff.diffForString.contramap(_.value)

  implicit def urlTinyTypeDiff[A <: UrlTinyType]: Diff[A] =
    Diff.diffForString.contramap(_.value)

  implicit def instantTinyTypeDiff[A <: InstantTinyType]: Diff[A] =
    Diff.diffForString.contramap(_.value.toString)

  implicit def localDateTinyTypeDiff[A <: LocalDateTinyType]: Diff[A] =
    Diff.diffForString.contramap(_.value.toString)

  implicit def durationTinyTypeDiff[A <: DurationTinyType]: Diff[A] =
    Diff.diffForString.contramap(_.value.toString)

  implicit def intTinyTypeDiff[A <: IntTinyType]: Diff[A] =
    Diff.diffForNumeric[Int].contramap(_.value)

  implicit def longTinyTypeDiff[A <: LongTinyType]: Diff[A] =
    Diff.diffForNumeric[Long].contramap(_.value)

  implicit def bigDecimalTinyTypeDiff[A <: BigDecimalTinyType]: Diff[A] =
    Diff.diffForNumeric[BigDecimal].contramap(_.value)

  implicit def floatTinyTypeDiff[A <: FloatTinyType]: Diff[A] =
    Diff.diffForNumeric[Float].contramap(_.value)

  implicit def boolTinyTypeDiff[A <: BooleanTinyType]: Diff[A] =
    Diff.diffForBoolean.contramap(_.value)

  implicit def relativePathTinyType[A <: RelativePathTinyType]: Diff[A] =
    Diff.diffForString.contramap(_.value)

  implicit def jsonDiff: Diff[Json] =
    Diff.diffForString.contramap(_.spaces2)

  implicit def jsonLDDiff: Diff[JsonLD] =
    jsonDiff.contramap(_.toJson)
}

object TinyTypeDiffInstances extends TinyTypeDiffInstances
