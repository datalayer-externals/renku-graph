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

package io.renku.triplesstore.client

import io.renku.triplesstore.client.model._
import io.renku.triplesstore.client.sparql.{Fragment, SparqlEncoder, StringInterpolator}

object syntax extends TripleObjectEncoder.Instances with SparqlEncoder.Instances {

  final implicit class TSClientObjectOps[T](private val obj: T) extends AnyVal {

    def asQuads(implicit enc: QuadsEncoder[T]): Set[Quad] = enc(obj)

    def asTripleObject(implicit enc: TripleObjectEncoder[T]): TripleObject = enc(obj)

    def asSparql(implicit enc: SparqlEncoder[T]): Fragment = enc(obj)
  }

  final implicit class FragmentStringContext(private val sc: StringContext) extends StringInterpolator(sc)
}
