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

package io.renku.graph.acceptancetests.stubs.gitlab

import GitLabApiStub._
import GitLabStateGenerators._
import cats.Monoid
import cats.syntax.all._
import cats.data.NonEmptyList
import io.renku.generators.Generators.Implicits._
import io.renku.graph.acceptancetests.data.Project
import io.renku.graph.model.entities.EntityFunctions
import io.renku.graph.model.events.CommitId
import io.renku.graph.model.testentities.{Person, Project => RenkuProject}
import io.renku.graph.model._
import io.renku.graph.model.gitlab.GitLabUser
import io.renku.http.client.UserAccessToken
import org.http4s.Uri

/** Collection of functions to update the state in [[GitLabApiStub]]. */
trait GitLabStateUpdates {
  type StateUpdate       = State => State
  type StateUpdateGet[A] = State => (State, A)

  final implicit class StateUpdateOps(self: StateUpdate) {
    def >>(next: StateUpdate): StateUpdate =
      self.andThen(next)
  }
  implicit def stateUpdateMonoid: Monoid[StateUpdate] =
    Monoid.instance(identity, _ >> _)

  def clearState: StateUpdate =
    _ => State.empty

  def addUser(id: persons.GitLabId, token: UserAccessToken): StateUpdate =
    state => state.copy(users = state.users.updated(id, token))

  def addWebhook(projectId: projects.GitLabId, url: Uri): StateUpdate = state => {
    val nextId = state.webhooks.size
    state.copy(webhooks =
      Webhook(nextId, projectId, url) :: state.webhooks.filter(p => p.projectId != projectId || p.url != url)
    )
  }

  def removeWebhook(projectId: projects.GitLabId, hookId: Int): StateUpdateGet[Boolean] = state => {
    val filtered = state.webhooks.filterNot(h => h.projectId == projectId && h.webhookId == hookId)
    (state.copy(webhooks = filtered), filtered != state.webhooks)
  }

  def removeWebhooks(projectId: projects.GitLabId): StateUpdate =
    state => state.copy(webhooks = state.webhooks.filterNot(_.projectId == projectId))

  def addCommitData(projectId: projects.GitLabId, commits: Seq[CommitData]): StateUpdate = state => {
    def connectParents(list: Seq[CommitData]): List[CommitData] =
      list.foldRight(List.empty[CommitData]) { (el, res) =>
        el.copy(parents = res.headOption.map(_.commitId).toList) :: res
      }

    val next = state.commits.updatedWith(projectId) {
      case Some(existing) => NonEmptyList.fromList(connectParents(commits ++ existing.toList))
      case None           => NonEmptyList.fromList(connectParents(commits))
    }
    state.copy(commits = next)
  }

  def addCommits(projectId: projects.GitLabId, commits: Seq[CommitId]): StateUpdate =
    addCommitData(projectId, commits.map(id => commitData(id).generateOne))

  def removeCommits(projectId: projects.GitLabId): StateUpdate =
    state => state.copy(commits = state.commits.removed(projectId))

  def replaceCommits(projectId: projects.GitLabId, commits: Seq[CommitId]): StateUpdate =
    removeCommits(projectId) >> addCommits(projectId, commits)

  def addPerson(person: Person*): StateUpdate =
    addPersons(person.toSet)

  def addPersons(persons: Iterable[Person]): StateUpdate = state => {
    val glIdPersons = persons.filter(_.maybeGitLabId.nonEmpty)
    val ids         = glIdPersons.flatMap(_.maybeGitLabId).toSet
    state.copy(persons = glIdPersons.toList ::: state.persons.filterNot(p => p.maybeGitLabId.exists(ids.contains)))
  }

  def addProject(project: Project): StateUpdate =
    state => state.copy(projects = project :: state.projects.filter(_.id != project.id))

  def addProject(project: Project, webhook: Uri): StateUpdate =
    addProject(project) >> addWebhook(project.id, webhook)

  def setupProject(project: Project, webhook: Uri, commits: CommitId*)(implicit renkuUrl: RenkuUrl): StateUpdate =
    addProject(project, webhook) >>
      addCommits(project.id, commits) >>
      addPersons(EntityFunctions[RenkuProject].findAllPersons(project.entitiesProject).map(toPerson)) >>
      addPersons((project.members.map(_.user) appendList project.maybeCreator.toList).map(userToPerson).toList)

  def addProjectAccessToken(projectId: projects.GitLabId, token: ProjectAccessTokenInfo): StateUpdate =
    state => state.copy(projectAccessTokens = state.projectAccessTokens.updated(projectId, token))

  private lazy val toPerson: entities.Person => Person = person =>
    Person(
      person.name,
      person.maybeEmail,
      person.maybeGitLabId,
      person.maybeOrcidId,
      person.maybeAffiliation
    )

  private lazy val userToPerson: GitLabUser => Person = { m =>
    Person(m.name, maybeEmail = m.email, maybeGitLabId = m.gitLabId.some)
  }

  def removeProject(projectId: projects.GitLabId): StateUpdate =
    removeCommits(projectId) >>
      removeWebhooks(projectId) >>
      (state => state.copy(projects = state.projects.filterNot(_.id == projectId)))

  def markProjectBroken(id: projects.GitLabId): StateUpdate =
    state => state.copy(brokenProjects = state.brokenProjects + id)

  def unmarkProjectBroken(id: projects.GitLabId): StateUpdate =
    state => state.copy(brokenProjects = state.brokenProjects - id)
}

object GitLabStateUpdates extends GitLabStateUpdates
