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

package io.renku.eventlog.events.producers

import Generators._
import cats.effect.IO
import cats.syntax.all._
import io.renku.events.Generators.{categoryNames, noCapacitySubscribers, subscriberCapacities}
import io.renku.events.Subscription.SubscriberUrl
import io.renku.generators.Generators.Implicits.GenOps
import io.renku.interpreters.TestLogger
import io.renku.interpreters.TestLogger.Level.Info
import io.renku.testtools.IOSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.OptionValues

import java.lang.Thread.sleep
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._

class SubscribersRegistrySpec
    extends AnyWordSpec
    with IOSpec
    with MockFactory
    with should.Matchers
    with Eventually
    with OptionValues {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(Span(30, Seconds)),
    interval = scaled(Span(100, Millis))
  )

  "add" should {

    "adds the given subscriber to the registry if it wasn't there yet" in new TestCase {

      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      registry.add(subscriber).unsafeRunSync() shouldBe false
    }

    "replaces the given subscriber in the registry " +
      "if there was one with the same URL although different id" in new TestCase {

        val initialCapacity = subscriberCapacities.generateOne
        registry.add(subscriber.copy(capacity = initialCapacity)).unsafeRunSync() shouldBe true
        registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync()         shouldBe subscriber.url

        val sameUrlSubscriberButOtherId = testSubscribers.generateOne
          .copy(
            url = subscriber.url,
            capacity = subscriberCapacities generateDifferentThan initialCapacity
          )
        registry.add(sameUrlSubscriberButOtherId).unsafeRunSync()         shouldBe false
        registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

        // this is to prove there's still one subscriber and the one with the new capacity
        registry.getTotalCapacity.value shouldBe TotalCapacity(sameUrlSubscriberButOtherId.capacity.value)
      }

    "move the given subscriber from the busy state to available" in new TestCase {

      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      registry.markBusy(subscriber.url).unsafeRunSync()                 shouldBe ()
      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url
    }

    "add the given subscriber if it was deleted" in new TestCase {

      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      registry.delete(subscriber.url).unsafeRunSync()                   shouldBe true
      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url
    }

    "don't add a subscriber twice even if it comes with different capacity" in new TestCase {

      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      val sameSubscriptionButWithDifferentCapacity = subscriber.copy(
        capacity = subscriberCapacities.generateDifferentThan(subscriber.capacity)
      )
      registry.add(sameSubscriptionButWithDifferentCapacity).unsafeRunSync() shouldBe false
    }
  }

  "findAvailableSubscriber" should {

    "not always return the same subscriber" in new TestCase {

      val subscribers = testSubscribers.generateNonEmptyList(min = 10, max = 20).toList

      subscribers.map(registry.add).sequence.unsafeRunSync()

      registry.subscriberCount() shouldBe subscribers.size

      val subscribersFound = (1 to 20).foldLeft(Set.empty[SubscriberUrl]) { (returnedSubscribers, _) =>
        returnedSubscribers + registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync()
      }
      subscribersFound.size should be > 1
    }

    "return subscribers from the available pool" in new TestCase {

      override val busySleep = 10 seconds

      val busySubscriber = testSubscribers.generateOne
      registry.add(busySubscriber).unsafeRunSync()                      shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe busySubscriber.url
      registry.markBusy(busySubscriber.url).unsafeRunSync()             shouldBe ((): Unit)

      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      val subscribersFound = (1 to 10).foldLeft(Set.empty[SubscriberUrl]) { (returnedSubscribers, _) =>
        returnedSubscribers + registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync()
      }
      subscribersFound shouldBe Set(subscriber.url)
    }

    "be able to queue callers when all subscribers are busy" in new TestCase {

      val collectedCallerIds = new ConcurrentHashMap[Unit, List[Int]]()
      collectedCallerIds.put((), List.empty[Int])

      val callerIds = (1 to 5).toList

      callerIds
        .map(callerId => IO(callFindSubscriber(callerId, collectedCallerIds)))
        .sequence
        .start
        .unsafeRunAndForget()

      Thread sleep 500

      registry.add(subscriber).unsafeRunSync() shouldBe true

      eventually {
        collectedCallerIds.get(()) shouldBe callerIds
      }
    }
  }

  "delete" should {

    "do nothing if the subscriber is not there" in new TestCase {
      registry.delete(subscriber.url).unsafeRunSync() shouldBe false
      registry.subscriberCount()                      shouldBe 0
    }

    "remove the subscriber if it's busy" in new TestCase {
      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      registry.markBusy(subscriber.url).unsafeRunSync() shouldBe ((): Unit)
      registry.subscriberCount()                        shouldBe 1

      registry.delete(subscriber.url).unsafeRunSync() shouldBe true
      registry.subscriberCount()                      shouldBe 0
    }
  }

  "markBusy" should {

    "make the subscriber temporarily unavailable" in new TestCase {

      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      val startTime = Instant.now()
      registry.markBusy(subscriber.url).unsafeRunSync() shouldBe ((): Unit)

      // this will block until the busy subscriber becomes available again
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url
      val endTime = Instant.now()

      (endTime.toEpochMilli - startTime.toEpochMilli) should be >= busySleep.toMillis
      (endTime.toEpochMilli - startTime.toEpochMilli) should be < (busySleep + checkupInterval + (300 millis)).toMillis

      eventually {
        logger.loggedOnly(Info(s"$categoryName: all 1 subscriber(s) are busy; waiting for one to become available"))
      }
    }

    "extend unavailable time if the subscriber is already unavailable" in new TestCase {

      registry.add(subscriber).unsafeRunSync()                          shouldBe true
      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url

      val startTime = Instant.now()
      registry.markBusy(subscriber.url).unsafeRunSync() shouldBe ((): Unit)

      sleep((busySleep - (100 millis)).toMillis)

      registry.markBusy(subscriber.url).unsafeRunSync() shouldBe ((): Unit)

      registry.findAvailableSubscriber().flatMap(_.get).unsafeRunSync() shouldBe subscriber.url
      val endTime = Instant.now()

      (endTime.toEpochMilli - startTime.toEpochMilli) should be > (busySleep - (100 millis) + busySleep).toMillis
      (endTime.toEpochMilli - startTime.toEpochMilli) should be < (busySleep * 2 + checkupInterval).toMillis
    }
  }

  "getTotalCapacity" should {

    "return None if there are no subscribers" in new TestCase {
      registry.getTotalCapacity shouldBe None
    }

    "return None if all subscribers have no capacity specified" in new TestCase {

      registry.add(noCapacitySubscribers.generateOne).unsafeRunSync() shouldBe true
      registry.add(noCapacitySubscribers.generateOne).unsafeRunSync() shouldBe true

      registry.getTotalCapacity shouldBe None
    }

    "sum up all the subscribers' capacities if specified" in new TestCase {

      val capacity1 = subscriberCapacities.generateOne
      registry.add(subscriber.copy(capacity = capacity1)).unsafeRunSync() shouldBe true

      val capacity2 = subscriberCapacities.generateOne
      registry
        .add(testSubscribers.generateOne.copy(capacity = capacity2))
        .unsafeRunSync() shouldBe true

      registry.getTotalCapacity shouldBe TotalCapacity(capacity1.value + capacity2.value).some
    }
  }

  private trait TestCase {

    val subscriber = testSubscribers.generateOne

    val categoryName    = categoryNames.generateOne
    val checkupInterval = 500 milliseconds
    val busySleep       = 500 milliseconds
    implicit val logger: TestLogger[IO] = TestLogger[IO]()
    lazy val registry = SubscribersRegistry[IO](categoryName, checkupInterval, busySleep).unsafeRunSync()

    def callFindSubscriber(callerId: Int, collectedCallers: ConcurrentHashMap[Unit, List[Int]]) = {

      def collectCallerId(callerId: Int) =
        collectedCallers.merge((), List(callerId), (t: List[Int], u: List[Int]) => t ++ u)

      registry
        .findAvailableSubscriber()
        .flatMap {
          _.get.map { ref =>
            collectCallerId(callerId)
            ref
          }
        }
        .unsafeRunSync() shouldBe subscriber.url
    }
  }
}
