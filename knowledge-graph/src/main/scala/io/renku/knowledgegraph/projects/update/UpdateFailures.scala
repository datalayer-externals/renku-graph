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

package io.renku.knowledgegraph.projects.update

import ProvisioningStatusFinder.ProvisioningStatus.Unhealthy
import cats.syntax.all._
import eu.timepit.refined.auto._
import io.circe.literal._
import io.renku.core.client.Branch
import io.renku.data.Message
import io.renku.graph.model.{persons, projects}
import io.renku.knowledgegraph.Failure
import io.renku.triplesgenerator.api.{ProjectUpdates => TGProjectUpdates}
import org.http4s.Status.{BadRequest, Conflict, Forbidden, InternalServerError}

private object UpdateFailures {

  def badRequestOnGLUpdate(message: Message): Failure =
    Failure(BadRequest, message)

  def forbiddenOnGLUpdate(message: Message): Failure =
    Failure(Forbidden, message)

  def onGLUpdate(slug: projects.Slug, cause: Throwable): Failure =
    Failure(InternalServerError,
            Message.Error.unsafeApply(show"Updating project $slug in GitLab failed.${toMessage(cause)}"),
            cause
    )

  def onTGUpdatesFinding(slug: projects.Slug, cause: Throwable): Failure =
    Failure(InternalServerError,
            Message.Error.unsafeApply(show"Finding Knowledge Graph updates for $slug failed"),
            cause
    )

  def onTSUpdate(slug: projects.Slug, cause: Throwable): Failure =
    Failure(InternalServerError,
            Message.Error.unsafeApply(show"Updating project $slug in the Knowledge Graph failed.${toMessage(cause)}"),
            cause
    )

  def onCoreUpdate(slug: projects.Slug, cause: Throwable): Failure =
    Failure(InternalServerError,
            Message.Error.unsafeApply(show"Updating project $slug in renku-core failed.${toMessage(cause)}"),
            cause
    )

  def corePushedToNonDefaultBranch(tgUpdates:      TGProjectUpdates,
                                   defaultBranch:  Option[DefaultBranch],
                                   corePushBranch: Branch
  ): Failure = {
    val updatedValuesInfo =
      if (tgUpdates == TGProjectUpdates.empty) "No values"
      else show"Only $tgUpdates"
    val defaultBranchInfo = defaultBranch.map(_.branch).fold("")(b => show" '$b'")
    val message =
      show"""|$updatedValuesInfo got updated in the Knowledge Graph due to branch protection rules on the default branch$defaultBranchInfo.
             | However, an update commit was pushed to a new branch '$corePushBranch' which has to be merged to the default branch with a PR""".stripMargin
        .filter(_ != '\n')
    val json = json"""{
      "message": $message,
      "branch":  $corePushBranch
    }"""
    Failure(Conflict, Message.Error.fromJsonUnsafe(json))
  }

  def onProvisioningNotHealthy(slug: projects.Slug, unhealthy: Unhealthy): Failure =
    Failure(
      Conflict,
      Message.Error.unsafeApply(
        show"Project $slug in unhealthy state: ${unhealthy.status}; Fix the project manually on contact administrator"
      )
    )

  def onProvisioningStatusCheck(slug: projects.Slug, cause: Throwable): Failure =
    Failure(InternalServerError, Message.Error.unsafeApply(show"Check if project $slug in healthy state failed"), cause)

  def onBranchAccessCheck(slug: projects.Slug, userId: persons.GitLabId, cause: Throwable): Failure =
    Failure(InternalServerError,
            Message.Error.unsafeApply(show"Check if pushing to git for $slug and $userId failed"),
            cause
    )

  val cannotFindProjectGitUrl: Failure =
    Failure(InternalServerError, Message.Error("Cannot find project info"))

  def onFindingProjectGitUrl(slug: projects.Slug, cause: Throwable): Failure =
    Failure(InternalServerError, Message.Error.unsafeApply(show"Finding project git url for $slug failed"), cause)

  def cannotFindUserInfo(userId: persons.GitLabId): Failure =
    Failure(InternalServerError, Message.Error.unsafeApply(show"Cannot find info about user $userId"))

  def onFindingUserInfo(userId: persons.GitLabId, cause: Throwable): Failure =
    Failure(InternalServerError, Message.Error.unsafeApply(show"Finding info about $userId failed"), cause)

  def onFindingCoreUri(cause: Throwable): Failure =
    Failure(Conflict, Message.Error.fromExceptionMessage(cause), cause)

  private def toMessage(cause: Throwable): String =
    Option(cause)
      .flatMap(c => Option(c.getMessage))
      .fold(ifEmpty = "")(m => s" $m")
}
