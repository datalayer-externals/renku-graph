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

package io.renku.tokenrepository.repository.fetching

import cats.MonadThrow
import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._
import eu.timepit.refined.types.numeric
import io.renku.graph.model.projects.{GitLabId, Slug}
import io.renku.http.client.AccessToken
import io.renku.tokenrepository.repository.AccessTokenCrypto
import io.renku.tokenrepository.repository.ProjectsTokensDB.SessionResource
import io.renku.tokenrepository.repository.metrics.QueriesExecutionTimes

import scala.util.control.NonFatal

private trait TokenFinder[F[_]] {
  def findToken(projectSlug: Slug):     OptionT[F, AccessToken]
  def findToken(projectId:   GitLabId): OptionT[F, AccessToken]
}

private class TokenFinderImpl[F[_]: MonadThrow](
    tokenInRepoFinder: PersistedTokensFinder[F],
    accessTokenCrypto: AccessTokenCrypto[F],
    maxRetries:        numeric.NonNegInt
) extends TokenFinder[F] {

  import accessTokenCrypto._
  import tokenInRepoFinder._

  override def findToken(projectSlug: Slug): OptionT[F, AccessToken] =
    findStoredToken(projectSlug) >>= { encryptedToken =>
      OptionT
        .liftF(decrypt(encryptedToken))
        .recoverWith(retry(() => findStoredToken(projectSlug)))
    }

  override def findToken(projectId: GitLabId): OptionT[F, AccessToken] =
    findStoredToken(projectId) >>= { encryptedToken =>
      OptionT
        .liftF(decrypt(encryptedToken))
        .recoverWith(retry(() => findStoredToken(projectId)))
    }

  private def retry(fetchToken:      () => OptionT[F, AccessTokenCrypto.EncryptedAccessToken],
                    numberOfRetries: Int = 0
  ): PartialFunction[Throwable, OptionT[F, AccessToken]] = { case NonFatal(exception) =>
    if (numberOfRetries < maxRetries.value) {
      for {
        encryptedToken <- fetchToken()
        accessToken    <- OptionT.liftF(decrypt(encryptedToken)) recoverWith retry(fetchToken, numberOfRetries + 1)
      } yield accessToken
    } else {
      OptionT.liftF(exception.raiseError[F, AccessToken])
    }
  }
}

private object TokenFinder {

  private val maxRetries = numeric.NonNegInt.unsafeFrom(3)

  def apply[F[_]: Async: SessionResource: QueriesExecutionTimes]: F[TokenFinder[F]] = for {
    accessTokenCrypto <- AccessTokenCrypto[F]()
  } yield new TokenFinderImpl[F](new PersistedTokensFinderImpl[F], accessTokenCrypto, maxRetries)
}
