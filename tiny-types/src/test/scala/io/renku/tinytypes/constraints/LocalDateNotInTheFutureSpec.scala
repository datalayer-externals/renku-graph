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

package io.renku.tinytypes.constraints

import io.renku.generators.Generators.localDatesNotInTheFuture
import io.renku.tinytypes.{LocalDateTinyType, TinyTypeFactory}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.LocalDate

class LocalDateNotInTheFutureSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  "LocalDateNotInTheFuture" should {

    "be instantiatable when values are LocalDates in the past" in {
      forAll(localDatesNotInTheFuture) { someValue =>
        LocalDateNotInTheFutureType(someValue).value shouldBe someValue
      }
    }

    "be instantiatable when values are LocalDate from now" in {
      val fixedNow = LocalDate.now

      LocalDateNotInTheFutureType.fetchNow = () => fixedNow

      LocalDateNotInTheFutureType(fixedNow).value shouldBe fixedNow
    }

    "throw an IllegalArgumentException for instants from the future" in {
      intercept[IllegalArgumentException] {
        LocalDateNotInTheFutureType(LocalDate.now().plusDays(1))
      }.getMessage shouldBe "io.renku.tinytypes.constraints.LocalDateNotInTheFutureType cannot be in the future"
    }
  }
}

private class LocalDateNotInTheFutureType private (val value: LocalDate) extends AnyVal with LocalDateTinyType
private object LocalDateNotInTheFutureType
    extends TinyTypeFactory[LocalDateNotInTheFutureType](new LocalDateNotInTheFutureType(_))
    with LocalDateNotInTheFuture[LocalDateNotInTheFutureType] {

  var fetchNow:               () => LocalDate = () => LocalDate.now()
  protected override def now: LocalDate       = fetchNow()
}
