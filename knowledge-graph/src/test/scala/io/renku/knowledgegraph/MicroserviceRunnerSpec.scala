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

package io.renku.knowledgegraph

import cats.MonadError
import cats.effect.{ExitCode, IO}
import io.renku.config.certificates.CertificateLoader
import io.renku.config.sentry.SentryInitializer
import io.renku.generators.Generators.Implicits._
import io.renku.generators.Generators.exceptions
import io.renku.http.server.HttpServer
import io.renku.knowledgegraph.MicroserviceRunnerSpec.CountEffect
import io.renku.knowledgegraph.metrics.KGMetrics
import io.renku.microservices.{AbstractMicroserviceRunnerTest, CallCounter, ServiceRunCounter}
import io.renku.testtools.IOSpec
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.duration._

class MicroserviceRunnerSpec extends AnyWordSpec with should.Matchers with IOSpec {

  "run" should {

    "return Success Exit Code if Sentry initializes and http server starts up" in new TestCase {

      startFor(1.second).unsafeRunSync() shouldBe ExitCode.Success
      assertCalledAll.unsafeRunSync()
    }

    "fail if Certificate loading fails" in new TestCase {

      val exception = exceptions.generateOne
      certificateLoader.failWith(exception).unsafeRunSync()

      intercept[Exception] {
        startRunnerForever.unsafeRunSync()
      } shouldBe exception
    }

    "fail if Sentry initialization fails" in new TestCase {

      val exception = exceptions.generateOne
      sentryInitializer.failWith(exception).unsafeRunSync()

      intercept[Exception] {
        startRunnerForever.unsafeRunSync()
      } shouldBe exception
    }

    "fail if starting the http server fails" in new TestCase {

      val exception = exceptions.generateOne
      httpServer.failWith(exception).unsafeRunSync()

      intercept[Exception] {
        startRunnerForever.unsafeRunSync()
      } shouldBe exception
    }

    "return Success ExitCode even if Knowledge Graph Metrics initialisation fails" in new TestCase {

      val exception = exceptions.generateOne
      kgMetrics.failWith(exception).unsafeRunSync()

      startFor(1.second).unsafeRunSync() shouldBe ExitCode.Success
      assertCalledAllBut(kgMetrics).unsafeRunSync()
    }
  }

  private trait TestCase extends AbstractMicroserviceRunnerTest {
    val context = MonadError[IO, Throwable]

    val certificateLoader: CertificateLoader[IO] with CallCounter = new CountEffect("CertificateLoader")
    val sentryInitializer: SentryInitializer[IO] with CallCounter = new CountEffect("SentryInitializer")
    val httpServer:        HttpServer[IO] with CallCounter        = new CountEffect("HttpServer")
    val kgMetrics:         KGMetrics[IO] with CallCounter         = new CountEffect("KgMetrics")
    val runner = new MicroserviceRunner(certificateLoader, sentryInitializer, httpServer, kgMetrics)

    val all: List[CallCounter] = List(
      certificateLoader,
      sentryInitializer,
      httpServer,
      kgMetrics
    )
  }
}

object MicroserviceRunnerSpec {
  class CountEffect(name: String) extends ServiceRunCounter(name) with KGMetrics[IO]
}
