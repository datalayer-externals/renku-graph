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

package io.renku.knowledgegraph.projects.details

import GitLabProjectFinder.GitLabProject
import KGProjectFinder.{KGParent, KGProject}
import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._
import cats.{MonadThrow, Parallel}
import io.renku.graph.model.projects.Slug
import io.renku.graph.tokenrepository.AccessTokenFinder
import io.renku.http.client.GitLabClient
import io.renku.http.server.security.model.AuthUser
import model._
import org.typelevel.log4cats.Logger

private trait ProjectFinder[F[_]] {
  def findProject(slug: Slug, maybeAuthUser: Option[AuthUser]): F[Option[Project]]
}

private class ProjectFinderImpl[F[_]: MonadThrow: Parallel: AccessTokenFinder](
    kgProjectFinder:     KGProjectFinder[F],
    gitLabProjectFinder: GitLabProjectFinder[F]
) extends ProjectFinder[F] {

  private val accessTokenFinder: AccessTokenFinder[F] = AccessTokenFinder[F]
  import accessTokenFinder._
  import gitLabProjectFinder.{findProject => findProjectInGitLab}
  import kgProjectFinder.{findProject => findInKG}

  def findProject(slug: Slug, maybeAuthUser: Option[AuthUser]): F[Option[Project]] =
    ((OptionT(findInKG(slug, maybeAuthUser)), findInGitLab(slug)) parMapN (merge(slug, _, _))).value

  private def findInGitLab(slug: Slug) =
    OptionT(findAccessToken(slug)) >>= { implicit accessToken => OptionT(findProjectInGitLab(slug)) }

  private def merge(slug: Slug, kgProject: KGProject, gitLabProject: GitLabProject) = Project(
    resourceId = kgProject.resourceId,
    id = gitLabProject.id,
    slug = slug,
    name = kgProject.name,
    maybeDescription = kgProject.maybeDescription,
    visibility = kgProject.visibility,
    created = Creation(
      date = kgProject.created.date,
      kgProject.created.maybeCreator.map(creator =>
        Creator(creator.resourceId, creator.name, creator.maybeEmail, creator.maybeAffiliation)
      )
    ),
    dateModified = kgProject.dateModified,
    urls = gitLabProject.urls,
    forking = Forking(gitLabProject.forksCount, kgProject.maybeParent.toParentProject),
    keywords = kgProject.keywords,
    starsCount = gitLabProject.starsCount,
    permissions = gitLabProject.permissions,
    statistics = gitLabProject.statistics,
    maybeVersion = kgProject.maybeVersion,
    images = kgProject.images
  )

  private implicit class ParentOps(maybeParent: Option[KGParent]) {
    lazy val toParentProject: Option[ParentProject] =
      maybeParent.map { case KGParent(resourceId, slug, name, created) =>
        ParentProject(
          resourceId,
          slug,
          name,
          Creation(created.date,
                   created.maybeCreator.map(creator =>
                     Creator(creator.resourceId, creator.name, creator.maybeEmail, creator.maybeAffiliation)
                   )
          )
        )
      }
  }
}

private object ProjectFinder {

  import io.renku.triplesstore.SparqlQueryTimeRecorder

  def apply[F[_]: Async: Parallel: GitLabClient: AccessTokenFinder: Logger: SparqlQueryTimeRecorder]
      : F[ProjectFinder[F]] = for {
    kgProjectFinder     <- KGProjectFinder[F]
    gitLabProjectFinder <- GitLabProjectFinder[F]
  } yield new ProjectFinderImpl(kgProjectFinder, gitLabProjectFinder)
}
