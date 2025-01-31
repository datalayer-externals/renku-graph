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

package io.renku.eventlog

import io.renku.data.Message
import io.renku.generators.Generators._
import io.renku.graph.model.events.{CreatedDate, EventDate, EventMessage, ExecutionDate}
import io.renku.tinytypes.constraints.{InstantNotInTheFuture, NonBlank}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant
import java.time.temporal.ChronoUnit.{HOURS, SECONDS}

class EventDateSpec extends AnyWordSpec with should.Matchers with ScalaCheckPropertyChecks {

  "instantiation" should {

    "succeed if less than a day in the future" in {
      forAll(timestamps(max = Instant.now().plus(24, HOURS).minus(1, SECONDS))) { value =>
        EventDate.from(value).map(_.value) shouldBe Right(value)
      }
    }

    "fail if further than a day in the future" in {
      forAll(timestamps(min = Instant.now().plus(24, HOURS).plus(1, SECONDS))) { value =>
        val Left(exception) = EventDate.from(value).map(_.value)
        exception          shouldBe an[IllegalArgumentException]
        exception.getMessage should startWith(s"${EventDate.typeName} has to be <= ")
      }
    }
  }
}

class CreatedDateSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  "CreatedDate" should {

    "have the InstantNotInTheFuture constraint" in {
      CreatedDate shouldBe an[InstantNotInTheFuture[_]]
    }

    "be instantiatable from any Instant not from the future" in {
      forAll(timestampsNotInTheFuture) { instant =>
        CreatedDate.from(instant).map(_.value) shouldBe Right(instant)
      }
    }
  }
}

class ExecutionDateSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  "ExecutionDate" should {

    "be instantiatable from any Instant" in {
      forAll(timestamps) { instant =>
        ExecutionDate.from(instant).map(_.value) shouldBe Right(instant)
      }
    }
  }
}

class EventMessageSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  "EventMessage" should {

    "have the NonBlank constraint" in {
      EventMessage shouldBe an[NonBlank[_]]
    }

    "be instantiatable from any non-blank string" in {
      forAll(nonEmptyStrings()) { body =>
        EventMessage.from(body).map(_.value) shouldBe Right(body)
      }
    }

    "be instantiatable from an exception and contain the stack trace" in {
      forAll(nestedExceptions) { exception =>
        EventMessage(exception).value shouldBe Message.Error.fromStackTrace(exception).show
      }
    }
  }
}
