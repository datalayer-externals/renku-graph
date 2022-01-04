/*
 * Copyright 2022 Swiss Data Science Center (SDSC)
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

package io.renku.triplesgenerator.events.categories.triplesgenerated

import cats.Applicative
import cats.data.EitherT
import cats.syntax.all._
import io.circe.Decoder
import io.circe.Decoder.decodeString
import io.renku.graph.model.entities.Project
import io.renku.http.client.RestClientError._
import io.renku.jsonld.EntityId
import io.renku.triplesgenerator.events.categories.Errors.ProcessingRecoverableError
import io.renku.triplesgenerator.events.categories.triplesgenerated.TransformationStep.Queries
import io.renku.triplesgenerator.events.categories.triplesgenerated.transformation.TransformationStepsCreator.TransformationRecoverableError

package object transformation {

  private[triplesgenerated] type TransformationResults[F[_]] = EitherT[F, ProcessingRecoverableError, Project]

  implicit val entityIdDecoder: Decoder[EntityId] =
    decodeString.emap { value =>
      if (value.trim.isEmpty) "Empty entityId found in the generated triples".asLeft[EntityId]
      else EntityId.of(value).asRight[String]
    }

  private[transformation] def maybeToRecoverableError[F[_]: Applicative](
      recoverableErrorMessage: String
  ): PartialFunction[Throwable, F[Either[ProcessingRecoverableError, (Project, Queries)]]] = {
    case e @ (_: UnexpectedResponseException | _: ConnectivityException | _: ClientException |
        _: UnauthorizedException) =>
      TransformationRecoverableError(recoverableErrorMessage, e)
        .asLeft[(Project, Queries)]
        .leftWiden[ProcessingRecoverableError]
        .pure[F]
  }
}
