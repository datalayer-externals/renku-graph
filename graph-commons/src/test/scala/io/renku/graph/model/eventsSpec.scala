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

package io.renku.graph.model

import cats.syntax.all._
import io.circe.Json
import io.renku.generators.Generators._
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.EventsGenerators._
import io.renku.graph.model.events._
import io.renku.graph.model.events.EventStatus._
import io.renku.tinytypes.constraints.{DurationNotNegative, NonBlank}
import org.scalatest.EitherValues
import org.scalatest.matchers.should
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import RenkuTinyTypeGenerators.personEmails

import java.time.{Clock, Instant, ZoneId, Duration => JavaDuration}
import java.time.temporal.ChronoUnit.{HOURS, SECONDS}
import scala.util.Random

class EventStatusSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers with EitherValues {

  "EventStatus" should {

    val scenarios = Table(
      "String Value" -> "Expected EventStatus",
      EventStatus.all.toList.map {
        case New                             => "NEW"                                -> New
        case Skipped                         => "SKIPPED"                            -> Skipped
        case GeneratingTriples               => "GENERATING_TRIPLES"                 -> GeneratingTriples
        case GenerationRecoverableFailure    => "GENERATION_RECOVERABLE_FAILURE"     -> GenerationRecoverableFailure
        case GenerationNonRecoverableFailure => "GENERATION_NON_RECOVERABLE_FAILURE" -> GenerationNonRecoverableFailure
        case TriplesGenerated                => "TRIPLES_GENERATED"                  -> TriplesGenerated
        case TransformingTriples             => "TRANSFORMING_TRIPLES"               -> TransformingTriples
        case TransformationRecoverableFailure =>
          "TRANSFORMATION_RECOVERABLE_FAILURE" -> TransformationRecoverableFailure
        case TransformationNonRecoverableFailure =>
          "TRANSFORMATION_NON_RECOVERABLE_FAILURE" -> TransformationNonRecoverableFailure
        case TriplesStore     => "TRIPLES_STORE"     -> TriplesStore
        case AwaitingDeletion => "AWAITING_DELETION" -> AwaitingDeletion
        case Deleting         => "DELETING"          -> Deleting
      }: _*
    )

    forAll(scenarios) { (stringValue, expectedStatus) =>
      s"be instantiatable from '$stringValue'" in {
        EventStatus.from(stringValue) shouldBe Right(expectedStatus)
      }

      s"be deserializable from $stringValue" in {
        Json.fromString(stringValue).as[EventStatus] shouldBe Right(expectedStatus)
      }
    }

    "fail instantiation for unknown value" in {
      val unknown = nonEmptyStrings().generateOne

      EventStatus.from(unknown).left.value.getMessage shouldBe s"'$unknown' unknown EventStatus"
    }

    "fail deserialization for unknown value" in {
      val unknown = nonEmptyStrings().generateOne

      Json.fromString(unknown).as[EventStatus].left.value.getMessage should include(s"'$unknown' unknown EventStatus")
    }

    "be sortable in the way that reflects the possible state changes" in {
      Random.shuffle(EventStatus.all.toList).sorted shouldBe List(
        Skipped,
        New,
        GeneratingTriples,
        GenerationRecoverableFailure,
        GenerationNonRecoverableFailure,
        TriplesGenerated,
        TransformingTriples,
        TransformationRecoverableFailure,
        TransformationNonRecoverableFailure,
        TriplesStore,
        AwaitingDeletion,
        Deleting
      )
    }
  }

  "ProcessingStatus" should {
    "group all EventStatuses that are of the ProcessingStatus type" in {
      ProcessingStatus.all shouldBe EventStatus.all.collect { case s: ProcessingStatus => s }
    }
  }
}

class EventStatusProgressSpec extends AnyWordSpec with TableDrivenPropertyChecks with should.Matchers {

  import EventStatusProgress._
  "apply" should {

    forAll {
      Table(
        ("status", "stage", "completion"),
        (New, Stage.Initial, 20f),
        (Skipped, Stage.Final, 100f),
        (GeneratingTriples, Stage.Generating, 40f),
        (GenerationRecoverableFailure, Stage.Generating, 40f),
        (GenerationNonRecoverableFailure, Stage.Final, 100f),
        (TriplesGenerated, Stage.Generated, 60f),
        (TransformingTriples, Stage.Transforming, 80f),
        (TransformationRecoverableFailure, Stage.Transforming, 80f),
        (TransformationNonRecoverableFailure, Stage.Final, 100f),
        (TriplesStore, Stage.Final, 100f),
        (AwaitingDeletion, Stage.Removing, 0f),
        (Deleting, Stage.Removing, 0f)
      )
    } { (status, stage, completion) =>
      show"return ProcessingProgress with stage '$stage', progress '$completion' for '$status' status" in {
        val progress = EventStatusProgress(status)
        progress.stage            shouldBe stage
        progress.completion.value shouldBe completion
      }
    }
  }
}

class CompoundEventIdSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  "toString" should {

    "be of format 'id = <eventId>, projectId = <projectId>'" in {
      forAll { commitEventId: CompoundEventId =>
        commitEventId.toString shouldBe s"id = ${commitEventId.id}, projectId = ${commitEventId.projectId}"
      }
    }
  }
}

class CommittedDateSpec extends AnyWordSpec with should.Matchers with ScalaCheckPropertyChecks {

  "instantiation" should {

    "succeed if less than a day in the future" in {
      forAll(timestamps(max = Instant.now().plus(24, HOURS).minus(1, SECONDS))) { value =>
        CommittedDate.from(value).map(_.value) shouldBe Right(value)
      }
    }

    "fail if further than a day in the future" in {
      forAll(timestamps(min = Instant.now().plus(24, HOURS).plus(1, SECONDS))) { value =>
        val Left(exception) = CommittedDate.from(value).map(_.value)
        exception          shouldBe an[IllegalArgumentException]
        exception.getMessage should startWith(s"${CommittedDate.typeName} has to be <= ")
      }
    }
  }
}

class EventBodySpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  import io.circe.Decoder
  import io.circe.literal._

  "EventBody" should {

    "have the NonBlank constraint" in {
      EventBody shouldBe an[NonBlank[_]]
    }

    "be instantiatable from any non-blank string" in {
      forAll(nonEmptyStrings()) { body =>
        EventBody.from(body).map(_.value) shouldBe Right(body)
      }
    }
  }

  "decodeAs" should {

    "parse the string value into Json and decode using the given decoder" in {
      val value = nonBlankStrings().generateOne.value

      case class Wrapper(value: String)
      implicit val decoder: Decoder[Wrapper] = Decoder.instance[Wrapper] {
        _.downField("field").as[String].map(Wrapper.apply)
      }

      val eventBody = EventBody {
        json"""{
          "field": $value
        }""".noSpaces
      }

      eventBody.decodeAs[Wrapper] shouldBe Right(Wrapper(value))
    }
  }

  "maybeAuthorEmail" should {

    "return value from author.email property if exists" in {

      val email = personEmails.generateOne
      val eventBody = EventBody {
        json"""{
          "author": {
            "email": $email
          }
        }""".noSpaces
      }

      eventBody.maybeAuthorEmail shouldBe email.some
    }

    "return None if author.email property invalid" in {

      val eventBody = EventBody {
        json"""{
          "author": {
            "email": true
          }
        }""".noSpaces
      }

      eventBody.maybeAuthorEmail shouldBe None
    }

    "return None if author.email property does not exist" in {
      EventBody("{}").maybeAuthorEmail shouldBe None
    }
  }

  "maybeCommitterEmail" should {

    "return value from committer.email property if exists" in {

      val email = personEmails.generateOne
      val eventBody = EventBody {
        json"""{
          "committer": {
            "email": $email
          }
        }""".noSpaces
      }

      eventBody.maybeCommitterEmail shouldBe email.some
    }

    "return None if committer.email property invalid" in {

      val eventBody = EventBody {
        json"""{
          "committer": {
            "email": true
          }
        }""".noSpaces
      }

      eventBody.maybeCommitterEmail shouldBe None
    }

    "return None if committer.email property does not exist" in {
      EventBody("{}").maybeCommitterEmail shouldBe None
    }
  }
}

class BatchDateSpec extends AnyWordSpec with should.Matchers {

  "apply()" should {

    "instantiate a new BatchDate with current timestamp" in {
      val fixedNow = Instant.now

      val clock = Clock.fixed(fixedNow, ZoneId.systemDefault())

      BatchDate(clock).value shouldBe fixedNow
    }
  }
}

class EventProcessingTimeSpec extends AnyWordSpec with ScalaCheckPropertyChecks with should.Matchers {

  "EventProcessingTime" should {

    "have the DurationNotNegative constraint" in {
      EventProcessingTime shouldBe an[DurationNotNegative[_]]
    }

    "be instantiatable from any non negative finite durations" in {
      forAll(notNegativeJavaDurations) { body =>
        EventProcessingTime.from(body).map(_.value) shouldBe Right(body)
      }
    }

    "throw an error if it is instantiated with a negative finite duration" in {
      forAll(
        javaDurations(min = -2000, max = -1)
      ) { duration =>
        val Left(exception) = EventProcessingTime.from(duration).map(_.value)
        exception          shouldBe an[IllegalArgumentException]
        exception.getMessage should startWith(s"${EventProcessingTime.typeName} cannot have a negative duration")
      }
    }
  }

  "*" should {

    "multiply the given processing time by the given value" in {
      forAll(eventProcessingTimes, positiveInts()) { (processingTime, multiplier) =>
        processingTime * multiplier shouldBe EventProcessingTime(
          JavaDuration.ofMillis(processingTime.value.toMillis * multiplier.value)
        )
      }
    }
  }

  "/" should {

    "divide the given processing time by the given value" in {
      forAll(eventProcessingTimes, positiveInts()) { (processingTime, multiplier) =>
        processingTime / multiplier shouldBe EventProcessingTime(
          JavaDuration.ofMillis(processingTime.value.toMillis / multiplier.value)
        )
      }
    }
  }
}
