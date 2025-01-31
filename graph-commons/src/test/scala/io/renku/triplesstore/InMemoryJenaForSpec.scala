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

import cats.effect.unsafe.IORuntime
import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.{BeforeAndAfter, Suite}

trait InMemoryJenaForSpec extends ForAllTestContainer with InMemoryJena with BeforeAndAfter with ResultsDecoder {
  self: Suite =>

  implicit val ioRuntime: IORuntime

  override def afterStart(): Unit = {
    super.afterStart()
    createDatasets().unsafeRunSync()
  }

  def clearDatasetsBefore: Boolean = true

  before {
    if (clearDatasetsBefore) clearAllDatasets()
  }
}
