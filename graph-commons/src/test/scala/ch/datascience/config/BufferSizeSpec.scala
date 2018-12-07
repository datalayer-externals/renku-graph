/*
 * Copyright 2018 Swiss Data Science Center (SDSC)
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

package ch.datascience.config

import ch.datascience.generators.Generators._
import org.scalacheck.Gen
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import org.scalatest.prop.PropertyChecks
import play.api.Configuration

class BufferSizeSpec extends WordSpec with PropertyChecks {

  "instantiation" should {

    "successfully read BufferSize from config for values less than 1" in {
      forAll( positiveInts() ) { positiveInt =>
        val config = Configuration.from(
          Map( "buffer-size" -> positiveInt )
        )

        config.get[BufferSize]( "buffer-size" ) shouldBe BufferSize( positiveInt )
      }
    }
    "fail for values less than 1" in {
      forAll( Gen.choose( -1000, 0 ) ) { nonPositiveInt =>
        val config = Configuration.from(
          Map( "buffer-size" -> nonPositiveInt )
        )

        an[IllegalArgumentException] should be thrownBy config.get[BufferSize]( "buffer-size" )
      }
    }
  }
}
