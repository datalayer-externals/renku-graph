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

package io.renku.core.client

import io.circe.Encoder
import io.circe.literal._
import io.circe.syntax._
import io.renku.graph.model.versions.{CliVersion, SchemaVersion}

private object TestModelCodecs {

  implicit val versionsEnc: Encoder[List[(SchemaVersion, CliVersion)]] = Encoder.instance { versions =>
    val versionJsons = versions.map { case (schema, cli) =>
      val cliValue = s"v$cli"
      json"""{
        "data": {
          "metadata_version": $schema
        },
        "version": $cliValue
      }"""
    }

    json"""{
      "name":     "renku-core",
      "versions": $versionJsons
    }"""
  }

  implicit val schemaApiVersionsEnc: Encoder[SchemaApiVersions] = Encoder.instance {
    case SchemaApiVersions(min, max, cliVersion) => json"""{
      "minimum_api_version": $min,
      "maximum_api_version": $max,
      "latest_version":      $cliVersion
    }"""
  }

  implicit val projectMigrationCheckEnc: Encoder[ProjectMigrationCheck] = Encoder.instance {
    case ProjectMigrationCheck(schemaVersion, migrationRequired) => json"""{
      "core_compatibility_status": {
        "project_metadata_version": $schemaVersion,
        "migration_required":       $migrationRequired
      }
    }"""
  }

  implicit def resultEncoder[T](implicit enc: Encoder[T]): Encoder[Result[T]] =
    Encoder.instance {
      case Result.Success(obj) => json"""{
          "result": ${obj.asJson}
        }"""
      case Result.Failure.Detailed(code, userMessage, maybeDevMessage) => json"""{
          "error": {
            "code":        $code,
            "userMessage": $userMessage,
            "devMessage":  $maybeDevMessage
          }
        }""".deepDropNullValues
      case result => throw new Exception(s"$result shouldn't be in the core API response payload")
    }
}
