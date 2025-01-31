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

package io.renku.commiteventservice.events.consumers.globalcommitsync.eventgeneration

import io.renku.commiteventservice.events.consumers.globalcommitsync.CommitsCount
import io.renku.graph.model.events.CommitId
import io.renku.graph.model.projects
import io.renku.http.rest.paging.model.Page

import java.time.Instant

private[globalcommitsync] final case class CommitWithParents(id:        CommitId,
                                                             projectId: projects.GitLabId,
                                                             parents:   List[CommitId]
)

private[globalcommitsync] final case class ProjectCommitStats(maybeLatestCommit: Option[CommitId],
                                                              commitsCount:      CommitsCount
)

private[globalcommitsync] final case class PageResult(commits: List[CommitId], maybeNextPage: Option[Page])
private[globalcommitsync] object PageResult {
  val empty: PageResult = PageResult(commits = Nil, maybeNextPage = None)
}

private[globalcommitsync] sealed trait DateCondition {
  val date: Instant
  def asQueryParameter: (String, String)
}

private[globalcommitsync] object DateCondition {

  final case class Since(date: Instant) extends DateCondition {
    override lazy val asQueryParameter: (String, String) = "since" -> date.toString
  }
  final case class Until(date: Instant) extends DateCondition {
    override lazy val asQueryParameter: (String, String) = "until" -> date.toString
  }
}
