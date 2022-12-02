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

package io.renku.tokenrepository.repository.refresh

import cats.effect.Async
import cats.syntax.all._
import io.renku.graph.model.projects
import io.renku.http.client.{AccessToken, GitLabClient}
import io.renku.tokenrepository.repository.association.TokenDates.ExpiryDate
import io.renku.tokenrepository.repository.association.renkuTokenName

private trait ExpiredTokensFinder[F[_]] {
  def findExpiredTokens(projectId: projects.Id, accessToken: AccessToken): F[List[AccessTokenId]]
}

private object ExpiredTokensFinder {
  def apply[F[_]: Async: GitLabClient] = new ExpiredTokensFinderImpl[F]
}

private class ExpiredTokensFinderImpl[F[_]: Async: GitLabClient] extends ExpiredTokensFinder[F] {

  import eu.timepit.refined.auto._
  import io.circe.Decoder
  import io.circe.Decoder._
  import org.http4s.Status.{Forbidden, NotFound, Ok, Unauthorized}
  import org.http4s.circe.jsonOf
  import org.http4s.implicits._
  import org.http4s.{EntityDecoder, Request, Response, Status}

  override def findExpiredTokens(projectId: projects.Id, accessToken: AccessToken): F[List[AccessTokenId]] =
    GitLabClient[F]
      .get(uri"projects" / projectId.value / "access_tokens", "project-access-tokens")(mapResponse)(accessToken.some)
      .map(_.filter(renkuTokens).filter(expired).map(_._1))

  private type TokenInfo = (AccessTokenId, String, Option[ExpiryDate])

  private lazy val mapResponse: PartialFunction[(Status, Request[F], Response[F]), F[List[TokenInfo]]] = {
    case (Ok, _, response)                           => response.as[List[TokenInfo]]
    case (Unauthorized | Forbidden | NotFound, _, _) => List.empty[TokenInfo].pure[F]
  }

  private implicit lazy val decoder: EntityDecoder[F, List[TokenInfo]] = {
    import io.renku.tinytypes.json.TinyTypeDecoders._

    implicit val itemDecoder: Decoder[TokenInfo] = cursor =>
      (cursor.downField("id").as[AccessTokenId],
       cursor.downField("name").as[String],
       cursor.downField("expires_at").as[Option[ExpiryDate]]
      ).mapN((t, n, e) => (t, n, e))

    jsonOf[F, List[TokenInfo]]
  }

  private lazy val renkuTokens: TokenInfo => Boolean = { case (_, name, _) =>
    name == renkuTokenName
  }

  private lazy val expired: TokenInfo => Boolean = {
    case (_, _, None)         => true
    case (_, _, Some(expiry)) => (expiry.value compareTo CloseExpirationDate().value) <= 0
  }
}
