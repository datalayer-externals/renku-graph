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

package io.renku.events

import io.renku.events
import io.renku.generators.Generators.{httpUrls, jsons, nonBlankStrings, nonEmptyStrings, positiveInts}
import io.renku.generators.Generators.Implicits._
import io.renku.tinytypes.ByteArrayTinyType
import io.renku.tinytypes.contenttypes.ZippedContent
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Gen._
import Subscription._
import cats.syntax.all._
import io.renku.events.DefaultSubscription.DefaultSubscriber
import io.renku.microservices.MicroserviceIdentifier

object Generators {

  val categoryNames: Gen[CategoryName] = nonBlankStrings(minLength = 5) map (value => CategoryName(value.value))

  final case class ZippedContentTinyType(value: Array[Byte]) extends ByteArrayTinyType with ZippedContent

  private val zippedContents: Gen[ByteArrayTinyType with ZippedContent] =
    Arbitrary.arbByte.arbitrary
      .toGeneratorOfList()
      .map(_.toArray)
      .generateAs(ZippedContentTinyType.apply)

  implicit val eventRequestContents: Gen[EventRequestContent] = for {
    event        <- jsons
    maybePayload <- oneOf[Any](nonEmptyStrings(), zippedContents).toGeneratorOfOptions
  } yield maybePayload match {
    case Some(payload) => events.EventRequestContent.WithPayload[Any](event, payload)
    case None          => events.EventRequestContent.NoPayload(event)
  }

  implicit val eventRequestContentNoPayloads: Gen[EventRequestContent.NoPayload] =
    jsons map events.EventRequestContent.NoPayload

  implicit val eventRequestContentWithZippedPayloads
      : Gen[EventRequestContent.WithPayload[ByteArrayTinyType with ZippedContent]] = for {
    event   <- jsons
    payload <- zippedContents
  } yield events.EventRequestContent.WithPayload(event, payload)

  implicit val subscriberIds:  Gen[SubscriberId]  = Gen.uuid map (_ => SubscriberId(MicroserviceIdentifier.generate))
  implicit val subscriberUrls: Gen[SubscriberUrl] = httpUrls() map SubscriberUrl.apply
  implicit val subscriberCapacities: Gen[SubscriberCapacity] = positiveInts().map(v => SubscriberCapacity(v.value))

  val noCapacitySubscribers: Gen[DefaultSubscriber.WithoutCapacity] =
    (subscriberUrls, subscriberIds).mapN(DefaultSubscriber.WithoutCapacity.apply)
  val capacitySubscribers: Gen[DefaultSubscriber.WithCapacity] =
    (subscriberUrls, subscriberIds, subscriberCapacities).mapN(DefaultSubscriber.WithCapacity.apply)
  val subscribers: Gen[DefaultSubscriber] = Gen.oneOf(noCapacitySubscribers, capacitySubscribers)

  val noCapacitySubscriptionPayloads: Gen[DefaultSubscription] =
    (categoryNames, noCapacitySubscribers).mapN(DefaultSubscription.apply)
  val capacitySubscriptionPayloads: Gen[DefaultSubscription] =
    (categoryNames, capacitySubscribers).mapN(DefaultSubscription.apply)
  val subscriptionPayloads: Gen[DefaultSubscription] =
    Gen.oneOf(noCapacitySubscriptionPayloads, capacitySubscriptionPayloads)
}
