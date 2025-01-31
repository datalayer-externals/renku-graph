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

package io.renku.db

import cats.effect.IO
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class SessionResourceResourceSpec extends AnyWordSpec with IOSpec with MockFactory with should.Matchers {

  "use" should {

    "pass the sessionResource built with the DBConfig to the given block" in new TestCase {

      transactedBlock.expects(*).returning(IO.unit)

      ssessionResource.use(transactedBlock).unsafeRunSync() shouldBe ()
    }
  }

  private trait TestCase {
    import natchez.Trace.Implicits.noop
    trait TestDB
    val transactedBlock  = mockFunction[SessionResource[IO, TestDB], IO[Unit]]
    val dbConfig         = TestDbConfig.create[TestDB]
    val ssessionResource = SessionPoolResource[IO, TestDB](dbConfig)
  }
}
