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

package io.renku.triplesgenerator.api

import cats.syntax.all._
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.RenkuTinyTypeGenerators._
import org.scalacheck.Gen

object Generators {

  val projectUpdatesGen: Gen[ProjectUpdates] =
    for {
      maybeNewDesc       <- projectDescriptions.toGeneratorOfOptions.toGeneratorOfOptions
      maybeNewImages     <- imageUris.toGeneratorOfList().toGeneratorOfOptions
      maybeNewKeywords   <- projectKeywords.toGeneratorOfSet(min = 0).toGeneratorOfOptions
      maybeNewVisibility <- projectVisibilities.toGeneratorOfOptions
    } yield ProjectUpdates(maybeNewDesc, maybeNewImages, maybeNewKeywords, maybeNewVisibility)

  val newProjectsGen: Gen[NewProject] =
    (projectNames,
     projectSlugs,
     projectDescriptions.toGeneratorOfOptions,
     projectCreatedDates(),
     (personNames, personGitLabIds).mapN(NewProject.Creator.apply),
     projectKeywords.toGeneratorOfSet(min = 0),
     projectVisibilities,
     imageUris.toGeneratorOfList()
    ).mapN(NewProject.apply)
}
