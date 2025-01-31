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

package io.renku.graph.model

import cats.data.{NonEmptyList, Validated}
import cats.kernel.Order
import cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import io.renku.graph.model.views.{EntityIdJsonLDOps, NonBlankTTJsonLDOps, TinyTypeJsonLDOps, UrlResourceRenderer}
import io.renku.jsonld.{EntityId, JsonLDDecoder, JsonLDEncoder}
import io.renku.tinytypes._
import io.renku.tinytypes.constraints._

import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit

object projects {

  sealed trait Identifier extends Any

  final class GitLabId private (val value: Int) extends AnyVal with IntTinyType with Identifier
  implicit object GitLabId
      extends TinyTypeFactory[GitLabId](new GitLabId(_))
      with NonNegativeInt[GitLabId]
      with TinyTypeJsonLDOps[GitLabId]

  final class GitLabPath private (val value: String) extends AnyVal with StringTinyType {
    def asName: Name = Name(value)
  }
  implicit object GitLabPath
      extends TinyTypeFactory[GitLabPath](new GitLabPath(_))
      with NonBlank[GitLabPath]
      with TinyTypeJsonLDOps[GitLabPath]

  final class Slug private (val value: String) extends AnyVal with RelativePathTinyType with Identifier
  implicit object Slug extends TinyTypeFactory[Slug](new Slug(_)) with RelativePath[Slug] with TinyTypeJsonLDOps[Slug] {
    private val allowedFirstChar         = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') :+ '_'
    private[projects] val regexValidator = "^([\\w.-]+)(\\/([\\w.-]+))+$"

    addConstraint(
      check = v => (v contains "/") && (allowedFirstChar contains v.head) && (v matches regexValidator),
      message = (value: String) => s"'$value' is not a valid $typeName"
    )

    implicit class SlugOps(slug: Slug) {
      private lazy val path :: namespacesStringReversed = slug.show.split('/').toList.reverse
      lazy val toPath:       GitLabPath      = GitLabPath(path)
      lazy val toNamespaces: List[Namespace] = namespacesStringReversed.reverseIterator.map(Namespace(_)).toList
      lazy val toNamespace:  Namespace       = toNamespaces.mkString("/")
    }
  }

  final class Namespace private (val value: String) extends AnyVal with StringTinyType
  implicit object Namespace
      extends TinyTypeFactory[Namespace](new Namespace(_))
      with NonBlank[Namespace]
      with TinyTypeJsonLDOps[Namespace]

  final class ResourceId private (val value: String) extends AnyVal with UrlTinyType
  implicit object ResourceId
      extends TinyTypeFactory[ResourceId](new ResourceId(_))
      with Url[ResourceId]
      with UrlResourceRenderer[ResourceId]
      with UrlOps[ResourceId]
      with EntityIdJsonLDOps[ResourceId] {

    private val regexValidator = s"^http[s]?:\\/\\/.*\\/projects\\/${Slug.regexValidator.drop(1)}"

    addConstraint(
      _ matches regexValidator,
      message = (value: String) => s"'$value' is not a valid $typeName"
    )

    def apply(projectSlug: Slug)(implicit renkuUrl: RenkuUrl): ResourceId =
      ResourceId((renkuUrl / "projects" / projectSlug).value)

    def apply(id: EntityId): ResourceId = ResourceId(id.value.toString)

    private val slugExtractor = "^.*\\/projects\\/(.*)$".r
    implicit lazy val projectSlugConverter: TinyTypeConverter[ResourceId, Slug] = {
      case ResourceId(slugExtractor(slug)) => Slug.from(slug)
      case illegalValue => Left(new IllegalArgumentException(s"'$illegalValue' cannot be converted to a ProjectSlug"))
    }
  }

  final class Name private (val value: String) extends AnyVal with StringTinyType
  implicit object Name extends TinyTypeFactory[Name](new Name(_)) with NonBlank[Name] with NonBlankTTJsonLDOps[Name]

  final class DateCreated private (val value: Instant) extends AnyVal with InstantTinyType
  implicit object DateCreated
      extends TinyTypeFactory[DateCreated](new DateCreated(_))
      with InstantNotInTheFuture[DateCreated]
      with TinyTypeJsonLDOps[DateCreated]

  final class DateModified private (val value: Instant) extends AnyVal with InstantTinyType
  implicit object DateModified
      extends TinyTypeFactory[DateModified](new DateModified(_))
      with InstantNotInTheFuture[DateModified]
      with TinyTypeJsonLDOps[DateModified]

  final class DateViewed private (val value: Instant) extends AnyVal with InstantTinyType
  implicit object DateViewed
      extends TinyTypeFactory[DateViewed](new DateViewed(_))
      with InstantNotInTheFuture[DateViewed]
      with TinyTypeJsonLDOps[DateViewed] {
    override val transform: Instant => Either[Throwable, Instant] = {
      val secondsPrecision: Instant => Instant = _.truncatedTo(ChronoUnit.SECONDS)
      // cannot use super on a val
      secondsPrecision.andThen(Right(_))
    }
  }

  final class FilePath private (val value: String) extends AnyVal with RelativePathTinyType
  object FilePath
      extends TinyTypeFactory[FilePath](new FilePath(_))
      with RelativePath[FilePath]
      with RelativePathOps[FilePath]
      with TinyTypeJsonLDOps[FilePath]

  final class Description private (val value: String) extends AnyVal with StringTinyType
  implicit object Description
      extends TinyTypeFactory[Description](new Description(_))
      with NonBlank[Description]
      with NonBlankTTJsonLDOps[Description]

  sealed trait Visibility extends StringTinyType with Product with Serializable {
    def compareTo(other: Visibility): Int =
      Visibility.allOrdered.indexOf(other) - Visibility.allOrdered.indexOf(this)
  }
  object Visibility extends TinyTypeFactory[Visibility](VisibilityInstantiator) {

    implicit val factory: From[Visibility] = this

    val allOrdered: List[Visibility] = List(Public, Internal, Private)
    val all:        Set[Visibility]  = allOrdered.toSet

    final case object Public extends Visibility {
      override val value: String = "public"
    }

    final case object Private extends Visibility {
      override val value: String = "private"
    }

    final case object Internal extends Visibility {
      override val value: String = "internal"
    }

    implicit override def ordering(implicit valueOrdering: Ordering[String]): Ordering[Visibility] =
      (x: Visibility, y: Visibility) => x compareTo y

    import io.circe.Decoder

    implicit lazy val jsonDecoder: Decoder[Visibility] =
      Decoder.decodeString.flatMap { decoded =>
        all.find(_.value == decoded) match {
          case Some(value) => Decoder.const(value)
          case None =>
            Decoder.failedWithMessage(
              s"'$decoded' is not a valid project visibility. Allowed values are: ${all.mkString(", ")}"
            )
        }
      }

    implicit lazy val jsonLDDecoder: JsonLDDecoder[Visibility] =
      JsonLDDecoder.decodeString.emap { decoded =>
        all.find(_.value == decoded) match {
          case Some(value) => value.asRight
          case None => s"'$decoded' is not a valid project visibility. Allowed values are: ${all.mkString(", ")}".asLeft
        }
      }

    implicit lazy val jsonLDEncoder: JsonLDEncoder[Visibility] = JsonLDEncoder.instance {
      case Private  => JsonLDEncoder.encodeString(Private.value)
      case Public   => JsonLDEncoder.encodeString(Public.value)
      case Internal => JsonLDEncoder.encodeString(Internal.value)
    }
  }

  private object VisibilityInstantiator extends (String => Visibility) {
    override def apply(value: String): Visibility = Visibility.all.find(_.value == value).getOrElse {
      throw new IllegalArgumentException(s"'$value' unknown Visibility")
    }
  }

  sealed trait ForksCount extends Any with IntTinyType

  object ForksCount {

    def apply(count: Int Refined Positive): NonZero = NonZero(count.value)

    case object Zero extends ForksCount { override val value: Int = 0 }
    type Zero = Zero.type

    final class NonZero private (val value: Int) extends AnyVal with ForksCount
    object NonZero extends TinyTypeFactory[NonZero](new NonZero(_)) with PositiveInt[NonZero]
  }

  final class Keyword private (val value: String) extends AnyVal with StringTinyType
  implicit object Keyword
      extends TinyTypeFactory[Keyword](new Keyword(_))
      with NonBlank[Keyword]
      with NonBlankTTJsonLDOps[Keyword]

  final class GitHttpUrl private (val value: String) extends AnyVal with StringTinyType
  implicit object GitHttpUrl extends TinyTypeFactory[GitHttpUrl](new GitHttpUrl(_)) with NonBlank[GitHttpUrl] {
    addConstraint(
      check = url =>
        (url endsWith ".git") && Validated
          .catchOnly[IllegalArgumentException](new URI(url).toURL)
          .isValid,
      message = url => s"$url is not a valid repository http url"
    )
  }

  sealed trait Role extends StringTinyType with Product with Ordered[Role] {
    def asString: String = value
  }

  object Role extends TinyTypeFactory[Role](RoleInstantiator) {
    case object Owner extends Role {
      override val value: String = "owner"

      override def compare(that: Role): Int =
        if (that == this) 0 else 1
    }

    case object Maintainer extends Role {
      override val value: String = "maintainer"

      override def compare(that: Role): Int =
        if (that == this) 0
        else if (that == Owner) -1
        else 1
    }

    case object Reader extends Role {
      override val value: String = "reader"

      override def compare(that: Role): Int =
        if (that == this) 0
        else -1
    }

    val all: NonEmptyList[Role] =
      NonEmptyList.of(Owner, Maintainer, Reader)

    def fromString(str: String): Either[String, Role] =
      all.find(_.asString.equalsIgnoreCase(str)).toRight(s"Invalid role name: $str")

    def unsafeFromString(str: String): Role =
      fromString(str).fold(sys.error, identity)

    /** Translated from here: https://docs.gitlab.com/ee/api/members.html#roles */
    def fromGitLabAccessLevel(accessLevel: Int): Role =
      accessLevel match {
        case n if n >= 50 => Owner
        case n if n >= 40 => Maintainer
        case _            => Reader
      }

    def toGitLabAccessLevel(role: Role): Int =
      role match {
        case Role.Owner      => 50
        case Role.Maintainer => 40
        case Role.Reader     => 20
      }

    implicit val ordering: Ordering[Role] =
      Ordering.by(r => -all.toList.indexOf(r))

    implicit val order: Order[Role] =
      Order.fromOrdering

    implicit val jsonDecoder: Decoder[Role] =
      Decoder.decodeString.emap(fromString)
  }

  private object RoleInstantiator extends (String => Role) {
    override def apply(value: String): Role =
      Role
        .fromString(value)
        .getOrElse(throw new IllegalArgumentException(s"'$value' unknown Role"))
  }
}
