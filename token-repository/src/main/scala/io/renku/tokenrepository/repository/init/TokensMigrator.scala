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
package init

import AccessTokenCrypto.EncryptedAccessToken
import ProjectsTokensDB.SessionResource
import cats.data.Kleisli
import cats.effect.{Async, Temporal}
import cats.syntax.all._
import creation.TokenDates.{CreatedAt, ExpiryDate}
import creation._
import deletion.PersistedTokenRemover
import io.renku.db.DbClient
import io.renku.graph.model.projects
import io.renku.http.client.{AccessToken, GitLabClient}
import io.renku.tokenrepository.repository.metrics.QueriesExecutionTimes
import org.typelevel.log4cats.Logger
import skunk._
import skunk.data.Completion.Delete

import scala.concurrent.duration._

private object TokensMigrator {
  def apply[F[_]: Async: GitLabClient: SessionResource: Logger: QueriesExecutionTimes]: F[DBMigration[F]] = for {
    accessTokenCrypto <- AccessTokenCrypto[F]()
    tokenValidator    <- TokenValidator[F]
    tokenRemover      <- PersistedTokenRemover[F].pure[F]
    tokensCreator     <- NewTokensCreator[F]()
  } yield new TokensMigrator[F](accessTokenCrypto, tokenValidator, tokenRemover, tokensCreator)
}

private class TokensMigrator[F[_]: Async: SessionResource: Logger: QueriesExecutionTimes](
    tokenCrypto:    AccessTokenCrypto[F],
    tokenValidator: TokenValidator[F],
    tokenRemover:   PersistedTokenRemover[F],
    tokensCreator:  NewTokensCreator[F],
    retryInterval:  Duration = 5 seconds
) extends DbClient[F](Some(QueriesExecutionTimes[F]))
    with DBMigration[F]
    with TokenRepositoryTypeSerializers {

  private val logPrefix = "token migration:"

  import MigrationTools._
  import fs2.Stream
  import io.renku.db.SqlStatement
  import skunk.Void
  import skunk.implicits._
  import tokenCrypto._
  import tokenValidator._
  import tokensCreator._

  override def run: F[Unit] = SessionResource[F].useK {
    checkColumnExists("projects_tokens", "project_slug").flatMapF {
      case true  => ().pure[F]
      case false => migrateTokens
    }
  }

  private def migrateTokens =
    Stream
      .repeatEval(findTokenWithoutDates)
      .unNoneTerminate
      .evalMap { case (proj, encToken) => decryptOrDelete(proj, encToken) }
      .flatMap(maybeProjectAndToken => Stream.emits(maybeProjectAndToken.toList))
      .evalMap { case (proj, token) => deleteWhenInvalidWithRetry(proj, token) }
      .flatMap(maybeProjectAndToken => Stream.emits(maybeProjectAndToken.toList))
      .evalMap { case (proj, token) => createTokenWithRetry(proj, token) }
      .flatMap(maybeProjectAndTokenInfo => Stream.emits(maybeProjectAndTokenInfo.toList))
      .evalMap { case (proj, newTokenInfo) => encrypt(newTokenInfo.token).map(enc => (proj, newTokenInfo, enc)) }
      .evalTap { case (proj, newTokenInfo, encToken) => persistWithRetry(proj, newTokenInfo, encToken) }
      .evalMap { case (proj, _, _) => Logger[F].info(show"$logPrefix $proj token created") }
      .compile
      .drain

  private def findTokenWithoutDates: F[Option[(Project, EncryptedAccessToken)]] = SessionResource[F].useK {
    measureExecutionTime {
      findTokenQuery.build(_.option)
    }
  }

  private def findTokenQuery: SqlStatement.Select[F, Void, (Project, EncryptedAccessToken)] =
    SqlStatement
      .named[F]("find non-migrated token")
      .select[Void, Project ~ EncryptedAccessToken](
        sql"""
        SELECT project_id, project_path, token
        FROM projects_tokens
        WHERE expiry_date IS NULL
        LIMIT 1"""
          .query(projectIdDecoder ~ projectSlugDecoder ~ encryptedAccessTokenDecoder)
          .map { case (id: projects.GitLabId) ~ (slug: projects.Slug) ~ (token: EncryptedAccessToken) =>
            Project(id, slug) -> token
          }
      )
      .arguments(Void)

  private def decryptOrDelete(project: Project, encToken: EncryptedAccessToken): F[Option[(Project, AccessToken)]] =
    decrypt(encToken)
      .map(t => Option(project -> t))
      .recoverWith { case _ => tokenRemover.delete(project.id) >> Option.empty[(Project, AccessToken)].pure[F] }

  private def deleteWhenInvalidWithRetry(project: Project, token: AccessToken): F[Option[(Project, AccessToken)]] = {
    checkValid(project.id, token) >>= {
      case true  => (project, token).some.pure[F]
      case false => tokenRemover.delete(project.id) >> Option.empty[(Project, AccessToken)].pure[F]
    }
  }.recoverWith(retry(deleteWhenInvalidWithRetry(project, token))(project))

  private def createTokenWithRetry(project: Project, token: AccessToken): F[Option[(Project, TokenCreationInfo)]] =
    createProjectAccessToken(project.id, token)
      .map(project -> _)
      .flatTapNone(
        Logger[F].warn(show"$logPrefix $project cannot generate new token; deleting") >> tokenRemover.delete(project.id)
      )
      .value
      .recoverWith(retry(createTokenWithRetry(project, token))(project))

  private def persistWithRetry(project:        Project,
                               newTokenInfo:   TokenCreationInfo,
                               encryptedToken: EncryptedAccessToken
  ): F[Unit] = SessionResource[F].useK {
    Kleisli { session =>
      (deleteToken(project) >> persistToken(project, encryptedToken, newTokenInfo.dates))
        .run(session)
        .recoverWith(retry(persistWithRetry(project, newTokenInfo, encryptedToken))(project))
    }
  }

  private def deleteToken(project: Project) = measureExecutionTime {
    SqlStatement
      .named(s"$logPrefix: delete token")
      .command[projects.GitLabId *: projects.Slug *: EmptyTuple](sql"""
        DELETE FROM projects_tokens
        WHERE project_id = $projectIdEncoder OR project_path = $projectSlugEncoder
      """.command)
      .arguments(project.id, project.slug)
      .build
      .flatMapResult {
        case Delete(_) => ().pure[F]
        case completion =>
          new Exception(
            show"$logPrefix $project deleting token for projectId = ${project.id}, projectSlug = ${project.slug} failed: $completion"
          ).raiseError[F, Unit]
      }
  }

  private def persistToken(project: Project, encryptedToken: EncryptedAccessToken, newTokenDates: TokenDates) =
    measureExecutionTime {
      SqlStatement
        .named[F](s"$logPrefix: persist new token")
        .command[projects.GitLabId *: projects.Slug *: EncryptedAccessToken *: CreatedAt *: ExpiryDate *: EmptyTuple](
          sql"""INSERT INTO projects_tokens (project_id, project_path, token, created_at, expiry_date)
                VALUES ($projectIdEncoder, $projectSlugEncoder, $encryptedAccessTokenEncoder, $createdAtEncoder, $expiryDateEncoder)
                ON CONFLICT DO NOTHING""".command
        )
        .arguments(
          project.id *: project.slug *: encryptedToken *: newTokenDates.createdAt *: newTokenDates.expiryDate *: EmptyTuple
        )
        .build
        .void
    }

  private def retry[O](thunk: => F[O])(project: Project): PartialFunction[Throwable, F[O]] = { case ex =>
    Logger[F].error(ex)(show"$logPrefix $project failure; retrying") >>
      Temporal[F].delayBy(thunk, retryInterval)
  }
}
