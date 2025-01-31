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

package io.renku.http.rest

import cats.Semigroup
import cats.data.NonEmptyList
import io.renku.triplesstore.client.model.OrderBy

/** Combines multiple [[SortBy.By]]s. */
final case class Sorting[S <: SortBy](sortBy: NonEmptyList[S#By]) {

  def toOrderBy(pm: S#PropertyType => OrderBy.Property): OrderBy =
    OrderBy(sortBy.map(e => OrderBy.Sort(pm(e.property), e.direction.toOrderByDirection)))

  lazy val asOrderBy: OrderBy = toOrderBy(p => OrderBy.Property(p.name))

  def :+(next: S#By): Sorting[S] =
    new Sorting(sortBy.append(next))

  def ++(next: Sorting[S]): Sorting[S] =
    new Sorting(this.sortBy.concatNel(next.sortBy))
}

object Sorting {
  def apply[E <: SortBy](e: E#By, more: E#By*): Sorting[E] =
    apply[E](NonEmptyList(e, more.toList))

  def apply[E <: SortBy](sorts: NonEmptyList[E#By]): Sorting[E] =
    new Sorting[E](sorts)

  def fromList[E <: SortBy](list: List[E#By]): Option[Sorting[E]] =
    NonEmptyList.fromList(list).map(Sorting(_))

  def fromListOrDefault[E <: SortBy](list: List[E#By], default: => Sorting[E]): Sorting[E] =
    fromList(list).getOrElse(default)

  def fromOptionalListOrDefault[E <: SortBy](maybeList: Option[List[E#By]], default: => Sorting[E]): Sorting[E] =
    fromListOrDefault(maybeList getOrElse Nil, default)

  implicit def sortingSemigroup[A <: SortBy]: Semigroup[Sorting[A]] =
    Semigroup.instance(_ ++ _)

}
