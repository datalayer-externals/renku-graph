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

package io.renku.triplesstore

import eu.timepit.refined.auto._
import io.renku.triplesstore.client.util.JenaRunMode
import org.scalatest.Suite

/** Use this trait as a replacement for [[InMemoryJenaForSpec]] to connect to a locally/externally running Jena without 
 * starting a container.  
 */
trait ExternalJenaForSpec extends InMemoryJenaForSpec {
  self: Suite =>

  /** Expect the external Jena instance to accept connections on the default port. */
  override val jenaRunMode: JenaRunMode = JenaRunMode.Local(3030)
}
