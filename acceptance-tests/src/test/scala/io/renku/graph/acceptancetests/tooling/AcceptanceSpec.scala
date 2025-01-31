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

package io.renku.graph.acceptancetests.tooling

import cats.effect.IO
import com.typesafe.config.{Config, ConfigFactory}
import io.renku.graph.config.RenkuUrlLoader
import io.renku.graph.model.RenkuUrl
import io.renku.testtools.IOSpec
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.typelevel.log4cats.Logger

import scala.util.Try

trait AcceptanceSpec extends AnyFeatureSpec with IOSpec with GivenWhenThen with should.Matchers {
  val testConfig: Config = ConfigFactory.load()

  implicit val testLogger: Logger[IO] = TestLogger()

  implicit val renkuUrl: RenkuUrl = RenkuUrlLoader[Try]().fold(throw _, identity)
}
