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

package io.renku.knowledgegraph.projects.update

import io.renku.core.client.Generators.branches
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.RenkuTinyTypeGenerators.{imageUris, projectDescriptions, projectKeywords, projectVisibilities}
import io.renku.knowledgegraph.projects.images.ImageGenerators.images
import org.scalacheck.Gen

private object Generators {

  val projectUpdatesGen: Gen[ProjectUpdates] =
    for {
      maybeNewDesc       <- projectDescriptions.toGeneratorOfOptions.toGeneratorOfOptions
      maybeNewImage      <- images.toGeneratorOfOptions.toGeneratorOfOptions
      maybeNewKeywords   <- projectKeywords.toGeneratorOfSet().toGeneratorOfOptions
      maybeNewVisibility <- projectVisibilities.toGeneratorOfOptions
    } yield ProjectUpdates(maybeNewDesc, maybeNewImage, maybeNewKeywords, maybeNewVisibility)

  val glUpdatedProjectsGen: Gen[GLUpdatedProject] =
    for {
      maybeNewImage      <- imageUris.toGeneratorOfOptions
      maybeNewVisibility <- projectVisibilities
    } yield GLUpdatedProject(maybeNewImage, maybeNewVisibility)

  val defaultBranchInfos: Gen[DefaultBranch] =
    branches
      .flatMap(branch => Gen.oneOf(DefaultBranch.PushProtected(branch), DefaultBranch.Unprotected(branch)))
}
