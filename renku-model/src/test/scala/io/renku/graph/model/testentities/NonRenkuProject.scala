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

package io.renku.graph.model.testentities

import io.renku.cli.model.CliProject
import io.renku.graph.model._
import io.renku.graph.model.cli.CliConverters
import io.renku.graph.model.images.ImageUri
import io.renku.graph.model.projects.{DateCreated, DateModified, Description, ForksCount, Keyword, Name, Slug, Visibility}
import io.renku.jsonld.JsonLDEncoder
import io.renku.jsonld.syntax._

sealed trait NonRenkuProject extends Project with Product with Serializable {
  def fold[A](f1: NonRenkuProject.WithParent => A, f2: NonRenkuProject.WithoutParent => A): A

  final def fold[A](
      f1: RenkuProject.WithParent => A,
      f2: RenkuProject.WithoutParent => A,
      f3: NonRenkuProject.WithParent => A,
      f4: NonRenkuProject.WithoutParent => A
  ): A = fold(f3, f4)
}

object NonRenkuProject {

  final case class WithoutParent(slug:             Slug,
                                 name:             Name,
                                 maybeDescription: Option[Description],
                                 dateCreated:      DateCreated,
                                 dateModified:     DateModified,
                                 maybeCreator:     Option[Person],
                                 visibility:       Visibility,
                                 forksCount:       ForksCount,
                                 keywords:         Set[Keyword],
                                 members:          Set[Project.Member],
                                 images:           List[ImageUri]
  ) extends NonRenkuProject {
    override type ProjectType = NonRenkuProject.WithoutParent
    def fold[A](f1: NonRenkuProject.WithParent => A, f2: NonRenkuProject.WithoutParent => A): A = f2(this)
  }

  final case class WithParent(slug:             Slug,
                              name:             Name,
                              maybeDescription: Option[Description],
                              dateCreated:      DateCreated,
                              dateModified:     DateModified,
                              maybeCreator:     Option[Person],
                              visibility:       Visibility,
                              forksCount:       ForksCount,
                              keywords:         Set[Keyword],
                              members:          Set[Project.Member],
                              parent:           NonRenkuProject,
                              images:           List[ImageUri]
  ) extends NonRenkuProject
      with Parent {
    override type ProjectType = NonRenkuProject.WithParent

    def fold[A](f1: NonRenkuProject.WithParent => A, f2: NonRenkuProject.WithoutParent => A): A = f1(this)
  }

  implicit def toEntitiesNonRenkuProject(implicit renkuUrl: RenkuUrl): NonRenkuProject => entities.NonRenkuProject = {
    case p: NonRenkuProject.WithParent    => toEntitiesNonRenkuProjectWithParent(renkuUrl)(p)
    case p: NonRenkuProject.WithoutParent => toEntitiesNonRenkuProjectWithoutParent(renkuUrl)(p)
  }

  implicit def toEntitiesNonRenkuProjectWithoutParent(implicit
      renkuUrl: RenkuUrl
  ): NonRenkuProject.WithoutParent => entities.NonRenkuProject.WithoutParent =
    project =>
      entities.NonRenkuProject.WithoutParent(
        projects.ResourceId(project.asEntityId),
        project.slug,
        project.name,
        project.maybeDescription,
        project.dateCreated,
        project.dateModified,
        project.maybeCreator.map(_.to[entities.Person]),
        project.visibility,
        project.keywords,
        project.members.map(_.to[entities.Project.Member]),
        convertImageUris(project.asEntityId)(project.images)
      )

  implicit def toEntitiesNonRenkuProjectWithParent(implicit
      renkuUrl: RenkuUrl
  ): NonRenkuProject.WithParent => entities.NonRenkuProject.WithParent =
    project =>
      entities.NonRenkuProject.WithParent(
        projects.ResourceId(project.asEntityId),
        project.slug,
        project.name,
        project.maybeDescription,
        project.dateCreated,
        project.dateModified,
        project.maybeCreator.map(_.to[entities.Person]),
        project.visibility,
        project.keywords,
        project.members.map(_.to[entities.Project.Member]),
        projects.ResourceId(project.parent.asEntityId),
        convertImageUris(project.asEntityId)(project.images)
      )

  def toCliNonRenkuProject(implicit renkuUrl: RenkuUrl): NonRenkuProject => CliProject =
    CliConverters.from(_)

  implicit def encoder[P <: NonRenkuProject](implicit
      renkuUrl:     RenkuUrl,
      gitLabApiUrl: GitLabApiUrl,
      graph:        GraphClass
  ): JsonLDEncoder[P] = JsonLDEncoder.instance {
    case project: NonRenkuProject.WithParent    => project.to[entities.NonRenkuProject.WithParent].asJsonLD
    case project: NonRenkuProject.WithoutParent => project.to[entities.NonRenkuProject.WithoutParent].asJsonLD
  }
}
