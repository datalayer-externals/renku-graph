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

package io.renku.webhookservice.crypto

import cats.MonadThrow
import cats.syntax.all._
import com.typesafe.config.{Config, ConfigFactory}
import eu.timepit.refined.W
import eu.timepit.refined.api.{RefType, Refined}
import eu.timepit.refined.string.MatchesRegex
import io.circe.parser._
import io.circe.{Decoder, HCursor, Json}
import io.renku.crypto.AesCrypto
import io.renku.crypto.AesCrypto.Secret
import io.renku.graph.model.projects.GitLabId
import io.renku.tinytypes.json.TinyTypeDecoders._
import io.renku.webhookservice.crypto.HookTokenCrypto.SerializedHookToken
import io.renku.webhookservice.model.HookToken

import scala.util.control.NonFatal

trait HookTokenCrypto[F[_]] {

  def decrypt(serializedToken: SerializedHookToken): F[HookToken]

  def encrypt(hookToken: HookToken): F[SerializedHookToken]
}

class HookTokenCryptoImpl[F[_]: MonadThrow](
    secret: Secret
) extends AesCrypto[F, HookToken, SerializedHookToken](secret)
    with HookTokenCrypto[F] {

  override def encrypt(hookToken: HookToken): F[SerializedHookToken] =
    for {
      serializedToken  <- serialize(hookToken).pure[F]
      encoded          <- encryptAndEncode(serializedToken)
      validatedDecoded <- validate(encoded)
    } yield validatedDecoded

  override def decrypt(serializedToken: SerializedHookToken): F[HookToken] = {
    for {
      decoded      <- decodeAndDecrypt(serializedToken.value)
      deserialized <- deserialize(decoded)
    } yield deserialized
  } recoverWith meaningfulError

  private def serialize(hook: HookToken): String =
    Json.obj("projectId" -> Json.fromInt(hook.projectId.value)).noSpaces

  private def validate(value: String): F[SerializedHookToken] = MonadThrow[F].fromEither[SerializedHookToken] {
    SerializedHookToken.from(value)
  }

  private implicit val hookTokenDecoder: Decoder[HookToken] = (cursor: HCursor) =>
    cursor.downField("projectId").as[GitLabId].map(HookToken)

  private def deserialize(json: String): F[HookToken] = MonadThrow[F].fromEither {
    parse(json).flatMap(_.as[HookToken])
  }

  private lazy val meaningfulError: PartialFunction[Throwable, F[HookToken]] = { case NonFatal(cause) =>
    MonadThrow[F].raiseError(new RuntimeException("HookToken decryption failed", cause))
  }
}

object HookTokenCrypto {

  import io.renku.config.ConfigLoader._

  def apply[F[_]: MonadThrow](config: Config = ConfigFactory.load()): F[HookTokenCrypto[F]] =
    find[F, Secret]("services.gitlab.hook-token-secret", config)
      .map(new HookTokenCryptoImpl[F](_))

  type SerializedHookToken = String Refined MatchesRegex[W.`"""^(?!\\s*$).+"""`.T]

  object SerializedHookToken {

    def from(value: String): Either[Throwable, SerializedHookToken] =
      RefType
        .applyRef[SerializedHookToken](value)
        .leftMap(_ => new IllegalArgumentException("A value to create HookToken cannot be blank"))
  }
}
