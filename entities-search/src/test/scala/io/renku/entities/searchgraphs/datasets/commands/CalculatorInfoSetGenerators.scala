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

package io.renku.entities.searchgraphs.datasets
package commands

import Generators._
import io.renku.graph.model.entities
import io.renku.graph.model.testentities._
import org.scalacheck.Gen

private object CalculatorInfoSetGenerators {

  lazy val calculatorInfoSets: Gen[CalculatorInfoSet] = for {
    project   <- anyRenkuProjectEntities(anyVisibility).map(_.to[entities.Project])
    modelInfo <- modelDatasetSearchInfoObjects(project)
    tsInfo    <- tsDatasetSearchInfoObjects(project)
  } yield CalculatorInfoSet.BothInfos(project.identification, modelInfo, tsInfo)
}
