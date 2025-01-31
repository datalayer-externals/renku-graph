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

package io.renku.tokenrepository.repository

import AccessTokenCrypto.EncryptedAccessToken
import cats.syntax.all._
import io.renku.graph.model.projects
import skunk.codec.all.{int4, varchar}
import skunk.{Decoder, Encoder}

trait TokenRepositoryTypeSerializers {

  val projectIdDecoder: Decoder[projects.GitLabId] = int4.map(projects.GitLabId.apply)
  val projectIdEncoder: Encoder[projects.GitLabId] = int4.values.contramap(_.value)

  val projectSlugDecoder: Decoder[projects.Slug] = varchar.map(projects.Slug.apply)
  val projectSlugEncoder: Encoder[projects.Slug] = varchar.values.contramap((b: projects.Slug) => b.value)

  private[repository] val encryptedAccessTokenDecoder: Decoder[EncryptedAccessToken] =
    varchar.emap(s => EncryptedAccessToken.from(s).leftMap(_.getMessage))
  private[repository] val encryptedAccessTokenEncoder: Encoder[EncryptedAccessToken] =
    varchar.values.contramap((b: EncryptedAccessToken) => b.value)
}
