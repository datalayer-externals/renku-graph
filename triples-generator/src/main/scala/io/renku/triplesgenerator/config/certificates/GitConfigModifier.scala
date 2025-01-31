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

package io.renku.triplesgenerator.config.certificates

import cats.MonadThrow
import org.typelevel.log4cats.Logger

import java.nio.file.Path

private trait GitConfigModifier[F[_]] {
  def makeGitTrust(certPath: Path): F[Unit]
}

private object GitConfigModifier {
  def apply[F[_]: MonadThrow: Logger]: GitConfigModifier[F] = new GitConfigModifierImpl[F]()
}

private class GitConfigModifierImpl[F[_]: MonadThrow] extends GitConfigModifier[F] {
  import ammonite.ops.{%%, home}
  import cats.syntax.all._

  override def makeGitTrust(certPath: Path): F[Unit] = MonadThrow[F].catchNonFatal {
    %%("git", "config", "--global", "http.sslCAInfo", certPath.toAbsolutePath.toString)(home)
  }.void
}
