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

package io.renku.http.jsonld4s

import fs2.Chunk
import io.circe.Printer
import io.renku.jsonld.JsonLD
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType}

trait JsonLD4sInstances {

  protected def defaultPrinter: Printer = Printer.noSpaces

  implicit def jsonLDEncoder[F[_]]: EntityEncoder[F, JsonLD] =
    jsonEncoderWithPrinter(defaultPrinter)

  def jsonEncoderWithPrinter[F[_]](printer: Printer): EntityEncoder[F, JsonLD] =
    EntityEncoder[F, Chunk[Byte]]
      .contramap[JsonLD](fromJsonToChunk(printer))
      .withContentType(`Content-Type`(MediaType.application.`ld+json`))

  private def fromJsonToChunk(printer: Printer)(jsonLD: JsonLD): Chunk[Byte] =
    Chunk.ByteBuffer.view(printer.printToByteBuffer(jsonLD.toJson))
}
