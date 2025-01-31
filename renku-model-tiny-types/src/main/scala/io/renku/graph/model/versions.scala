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

import cats.Show
import cats.syntax.show._
import io.circe.Decoder
import io.renku.graph.model.views.NonBlankTTJsonLDOps
import io.renku.tinytypes._
import io.renku.tinytypes.constraints.NonBlank
import io.renku.tinytypes.json.TinyTypeDecoders

object versions {

  final class CliVersion private (val value: String) extends AnyVal with StringTinyType
  object CliVersion
      extends TinyTypeFactory[CliVersion](new CliVersion(_))
      with NonBlankTTJsonLDOps[CliVersion]
      with NonBlank[CliVersion] {

    private val validationRegex: String = raw"\d+\.\d+\.\d+.*"

    addConstraint(
      check = _ matches validationRegex,
      message = (value: String) => s"'$value' is not a valid CLI version"
    )

    implicit class CliVersionAlg(cliVersion: CliVersion) {
      val s"$major.$minor.$bugfix" = cliVersion.value
    }

    implicit val jsonDecoder: Decoder[CliVersion] = TinyTypeDecoders.stringDecoder(this)

    implicit val ordering: Ordering[CliVersion] = (x: CliVersion, y: CliVersion) =>
      if ((x.major compareTo y.major) != 0) x.major compareTo y.major
      else if ((x.minor compareTo y.minor) != 0) x.minor compareTo y.minor
      else if ((x.bugfix compareTo y.bugfix) != 0) x.bugfix compareTo y.bugfix
      else x.value compareTo y.value
  }

  final case class RenkuVersionPair(cliVersion: CliVersion, schemaVersion: SchemaVersion)
      extends Product
      with Serializable

  object RenkuVersionPair {
    implicit lazy val versionPairDecoder: Decoder[List[RenkuVersionPair]] = { topCursor =>
      val renkuVersionPairs: Decoder[RenkuVersionPair] = { cursor =>
        for {
          cliVersion    <- cursor.downField("cliVersion").downField("value").as[CliVersion]
          schemaVersion <- cursor.downField("schemaVersion").downField("value").as[SchemaVersion]
        } yield RenkuVersionPair(cliVersion, schemaVersion)
      }
      topCursor.downField("results").downField("bindings").as(Decoder.decodeList(renkuVersionPairs))
    }

    implicit lazy val show: Show[RenkuVersionPair] = Show.show { case RenkuVersionPair(cliVersion, schemaVersion) =>
      show"cliVersion: $cliVersion, schemaVersion: $schemaVersion"
    }
  }

  final class SchemaVersion private (val value: String) extends AnyVal with StringTinyType
  object SchemaVersion
      extends TinyTypeFactory[SchemaVersion](new SchemaVersion(_))
      with NonBlank[SchemaVersion]
      with NonBlankTTJsonLDOps[SchemaVersion] {
    implicit val jsonDecoder: Decoder[SchemaVersion] = TinyTypeDecoders.stringDecoder(SchemaVersion)
  }
}
