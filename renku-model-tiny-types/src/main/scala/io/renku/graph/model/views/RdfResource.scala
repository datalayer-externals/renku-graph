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

package io.renku.graph.model.views

import cats.syntax.all._
import io.renku.jsonld.EntityId
import io.renku.tinytypes._
import io.renku.tinytypes.constraints.Url
import io.renku.triplesstore.client.syntax.entityIdSparqlEncoder

trait RdfResource

trait UrlResourceRenderer[T <: UrlTinyType] {
  self: TinyTypeFactory[T] with Url[T] =>

  implicit val rdfResourceRenderer: Renderer[RdfResource, T] = url =>
    entityIdSparqlEncoder(EntityId.of(url.show)).sparql
}

trait AnyResourceRenderer[T <: StringTinyType] {
  self: TinyTypeFactory[T] with Url[T] =>

  implicit val rdfResourceRenderer: Renderer[RdfResource, T] = url =>
    entityIdSparqlEncoder(EntityId.of(url.show)).sparql
}
