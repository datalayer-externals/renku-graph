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

package io.renku.eventsqueue

import cats.data.Kleisli
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Deferred, IO, Temporal}
import fs2.Stream
import org.scalatest.Succeeded
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should
import skunk._
import skunk.implicits._

import scala.concurrent.duration._
import scala.util.Random

class ChannelsSpec extends AsyncFlatSpec with AsyncIOSpec with EventsQueueDBSpec with should.Matchers {

  it should "use pg async notification mechanism for pub/sub purposes" ignore {
    val sentAll = Deferred.unsafe[IO, Unit]
    for {
      _ <- keepNotifying(sentAll).start
      _ <- Temporal[IO].sleep(5 seconds)
      _ <- listen("listener1").start
      _ <- listen("listener2").start
      _ <- sentAll.get
    } yield Succeeded
  }

  private val channelId = id"chnl"
  type Ch = Channel[IO, String, String]

  private def withChannel(f: Ch => IO[Unit]): IO[Unit] =
    execute {
      Kleisli.fromFunction[IO, Session[IO]](_.channel(channelId)).flatMapF(f)
    }

  private def keepNotifying(sentAll: Deferred[IO, Unit]): IO[Unit] = withChannel { ch =>
    Stream
      .iterate(1)(_ + 1)
      .evalMap { i =>
        println(s"sending $i")
        Temporal[IO].delayBy(ch.notify(i.toString), Random.nextInt(1000) millis)
      }
      .take(25)
      .compile
      .drain
      .flatMap(_ => sentAll.complete(()).void)
  }

  private def listen(listenerId: String): IO[Unit] = withChannel { ch =>
    ch.listen(20)
      .map(n => println(s"$listenerId received: ${n.value}; from channel '${n.channel}'"))
      .evalMap(_ => Temporal[IO].sleep(Random.nextInt(1000) millis))
      .compile
      .drain
  }
}
