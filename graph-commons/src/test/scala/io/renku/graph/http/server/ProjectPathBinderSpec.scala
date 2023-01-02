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

package io.renku.graph.http.server

import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model.GraphModelGenerators.projectPaths
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class ProjectPathBinderSpec extends AnyWordSpec with should.Matchers {

  import binders._

  "unapply" should {

    "convert valid project path as string to a ProjectPath" in {
      val projectPath = projectPaths.generateOne

      val Some(result) = ProjectPath.unapply(projectPath.value)

      result shouldBe projectPath
    }

    "return None if string value cannot be converted to a ProjectPath" in {
      ProjectPath.unapply(blankStrings().generateOne) shouldBe None
    }
  }
}
