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

package io.renku.knowledgegraph
package projects.delete

import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.data.Message
import io.renku.knowledgegraph.docs.model.Operation.DELETE
import io.renku.knowledgegraph.docs.model._

object EndpointDocs extends docs.EndpointDocs {

  override lazy val path: Path = Path(
    DELETE(
      "Project Delete",
      "Deletes a Project with the given slug",
      Uri / "projects" / namespace / projectName,
      Status.Accepted -> Response(
        "Project deleted",
        Contents(MediaType.`application/json`("Project deleted", Message.Info("Project deleted")))
      ),
      Status.Unauthorized -> Response(
        "Unauthorized",
        Contents(MediaType.`application/json`("Invalid token", Message.Info("Unauthorized")))
      ),
      Status.NotFound -> Response(
        "Project not found",
        Contents(MediaType.`application/json`("Reason", Message.Info("Project does not exist")))
      ),
      Status.InternalServerError -> Response("Error",
                                             Contents(MediaType.`application/json`("Reason", Message.Info("Message")))
      )
    )
  )

  private lazy val namespace = Parameter.Path(
    "namespace",
    Schema.String,
    description =
      "Namespace(s) as there might be multiple. Each namespace needs to be url-encoded and separated with a non url-encoded '/'".some
  )

  private lazy val projectName = Parameter.Path("projectName", Schema.String, "Project name".some)
}
