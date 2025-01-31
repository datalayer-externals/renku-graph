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

package io.renku.events.consumers.subscriptions

import cats.{Applicative, MonadThrow}
import cats.data.Kleisli
import cats.effect.Async
import cats.effect.kernel.Temporal
import cats.syntax.all._
import io.circe.Encoder
import io.renku.events.{CategoryName, Subscription}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

trait SubscriptionMechanism[F[_]] {
  def categoryName:        CategoryName
  def renewSubscription(): F[Unit]
  def run():               F[Unit]
}

private class SubscriptionMechanismImpl[F[_]: MonadThrow: Temporal: Logger, S <: Subscription](
    val categoryName:            CategoryName,
    subscriptionPayloadComposer: SubscriptionPayloadComposer[F, S],
    subscriptionSender:          SubscriptionSender[F],
    initialDelay:                FiniteDuration,
    renewDelay:                  FiniteDuration
)(implicit subscriptionPayloadEncoder: Encoder[S])
    extends SubscriptionMechanism[F] {

  import cats.effect.kernel.Ref

  private val applicative = Applicative[F]

  import applicative._
  import cats.syntax.all._
  import io.circe.syntax._
  import subscriptionPayloadComposer._
  import subscriptionSender._

  override def renewSubscription(): F[Unit] = {
    prepareSubscriptionPayload().map(_.asJson) >>= postToEventLog
  } recoverWith { case NonFatal(exception) =>
    Logger[F].error(exception)(s"$categoryName: Problem with notifying event-log") >>
      exception.raiseError[F, Unit]
  }

  override def run(): F[Unit] =
    Temporal[F]
      .delayBy(Ref.of[F, Boolean](true), initialDelay)
      .flatMap(subscribeForEvents(_).foreverM[Unit])

  private def subscribeForEvents(initOrError: Ref[F, Boolean]): F[Unit] = {
    for {
      _            <- ().pure[F]
      payload      <- prepareSubscriptionPayload()
      postingError <- postToEventLog(payload.asJson).map(_ => false).recoverWith(logPostError)
      shouldLog    <- initOrError getAndSet postingError
      _            <- whenA(shouldLog && !postingError)(logInfo(payload))
      _            <- Temporal[F] sleep renewDelay
    } yield ()
  } recoverWith logSubscriberUrlError

  private lazy val logSubscriberUrlError: PartialFunction[Throwable, F[Unit]] = { case NonFatal(exception) =>
    Temporal[F].andWait(
      Logger[F].error(exception)(s"$categoryName: Composing subscription payload failed"),
      initialDelay
    )
  }

  private lazy val logPostError: PartialFunction[Throwable, F[Boolean]] = { case NonFatal(exception) =>
    Temporal[F].andWait(
      Logger[F].error(exception)(s"$categoryName: Subscribing for events failed").map(_ => true),
      initialDelay
    )
  }

  private def logInfo: Subscription => F[Unit] = { subscription =>
    Logger[F].info(
      show"$categoryName: Subscribed for events with ${subscription.subscriber.url}, id = ${subscription.subscriber.id}"
    )
  }
}

object SubscriptionMechanism {
  import com.typesafe.config.{Config, ConfigFactory}
  import io.renku.config.ConfigLoader.find

  import scala.concurrent.duration._

  def apply[F[_]: Async: Logger, S <: Subscription](
      categoryName:                       CategoryName,
      subscriptionPayloadComposerFactory: Kleisli[F, CategoryName, SubscriptionPayloadComposer[F, S]],
      configuration:                      Config = ConfigFactory.load()
  )(implicit subscriptionEncoder: Encoder[S]): F[SubscriptionMechanism[F]] = for {
    initialDelay                <- find[F, FiniteDuration]("event-subscription-initial-delay", configuration)
    renewDelay                  <- find[F, FiniteDuration]("event-subscription-renew-delay", configuration)
    subscriptionPayloadComposer <- subscriptionPayloadComposerFactory(categoryName)
    subscriptionSender          <- SubscriptionSender[F]
  } yield new SubscriptionMechanismImpl[F, S](categoryName,
                                              subscriptionPayloadComposer,
                                              subscriptionSender,
                                              initialDelay,
                                              renewDelay
  )

  def noOpSubscriptionMechanism[F[_]: MonadThrow](category: CategoryName): SubscriptionMechanism[F] =
    new SubscriptionMechanism[F] {

      override lazy val categoryName: CategoryName = category

      override def renewSubscription(): F[Unit] = MonadThrow[F].unit

      override def run(): F[Unit] = MonadThrow[F].unit
    }
}
