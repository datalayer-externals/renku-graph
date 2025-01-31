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

package io.renku.entities.search

import Criteria.Filters._
import Criteria._
import EntityConverters._
import cats.syntax.all._
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators._
import io.renku.graph.model._
import io.renku.graph.model.testentities._
import io.renku.testtools.IOSpec
import io.renku.triplesstore.{InMemoryJenaForSpec, ProjectsDataset}
import org.scalacheck.Gen.alphaLowerChar
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class PersonsEntitiesFinderSpec
    extends AnyWordSpec
    with should.Matchers
    with FinderSpecOps
    with InMemoryJenaForSpec
    with ProjectsDataset
    with IOSpec {

  "findEntities - persons" should {

    "return a single person if there are multiple with the same name" in new TestCase {
      // person merging is a temporary solution until we start to return persons ids

      val query = nonBlankStrings(minLength = 3, charsGenerator = alphaLowerChar).generateOne

      val sharedName      = sentenceContaining(query).generateAs(persons.Name)
      val person1SameName = personEntities.map(_.copy(name = sharedName)).generateOne
      val person2SameName = personEntities.map(_.copy(name = sharedName)).generateOne
      val person3 = personEntities
        .map(_.copy(name = sentenceContaining(query).generateAs(persons.Name)))
        .generateOne

      upload(to = projectsDataset, person1SameName, person2SameName, person3, personEntities.generateOne)

      finder
        .findEntities(Criteria(Filters(maybeQuery = Query(query.value).some)))
        .unsafeRunSync()
        .resultsWithSkippedMatchingScore
        .sortBy(_.name)(nameOrdering) should {
        be(List(person1SameName, person3).map(_.to[model.Entity.Person]).sortBy(_.name)(nameOrdering)) or
          be(List(person2SameName, person3).map(_.to[model.Entity.Person]).sortBy(_.name)(nameOrdering))
      }
    }
  }
}
