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

package io.renku.triplesgenerator.tsprovisioning
package triplesuploading

import cats.MonadThrow
import cats.data.EitherT
import cats.effect.Async
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import io.renku.http.client.RestClient.{MaxRetriesAfterConnectionTimeout, SleepAfterConnectionIssue}
import io.renku.jsonld.JsonLD
import io.renku.triplesgenerator.errors.{ProcessingRecoverableError, RecoverableErrorsRecovery}
import io.renku.triplesstore._
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

private trait JsonLDUploader[F[_]] {
  def uploadJsonLD(triples: JsonLD): EitherT[F, ProcessingRecoverableError, Unit]
}

private class JsonLDUploaderImpl[F[_]: Async: Logger: SparqlQueryTimeRecorder](
    dsConnectionConfig: DatasetConnectionConfig,
    recoveryStrategy:   RecoverableErrorsRecovery = RecoverableErrorsRecovery,
    retryInterval:      FiniteDuration = SleepAfterConnectionIssue,
    maxRetries:         Int Refined NonNegative = MaxRetriesAfterConnectionTimeout
) extends TSClientImpl(dsConnectionConfig, retryInterval = retryInterval, maxRetries = maxRetries)
    with JsonLDUploader[F] {

  import org.http4s.Status._
  import org.http4s.{Request, Response, Status}
  import recoveryStrategy.maybeRecoverableError

  override def uploadJsonLD(triples: JsonLD): EitherT[F, ProcessingRecoverableError, Unit] = EitherT {
    uploadAndMap[Either[ProcessingRecoverableError, Unit]](triples)(responseMapping)
      .recoverWith(maybeRecoverableError)
  }

  private lazy val responseMapping
      : PartialFunction[(Status, Request[F], Response[F]), F[Either[ProcessingRecoverableError, Unit]]] = {
    case (Ok, _, _) => ().asRight[ProcessingRecoverableError].pure[F]
  }
}

private object JsonLDUploader {
  def apply[F[_]: Async: Logger: SparqlQueryTimeRecorder](
      storeConfig:   ProjectsConnectionConfig,
      retryInterval: FiniteDuration = SleepAfterConnectionIssue,
      maxRetries:    Int Refined NonNegative = MaxRetriesAfterConnectionTimeout
  ): F[JsonLDUploaderImpl[F]] = MonadThrow[F].catchNonFatal(
    new JsonLDUploaderImpl[F](storeConfig, RecoverableErrorsRecovery, retryInterval, maxRetries)
  )
}
