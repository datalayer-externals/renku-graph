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

package io.renku.graph.model.entities

import cats.data.ValidatedNel
import cats.syntax.all._
import io.renku.cli.model.CliPerson
import io.renku.graph.model.persons.{Affiliation, Email, GitLabId, Name, OrcidId, ResourceId}
import io.renku.graph.model.{GitLabApiUrl, GraphClass, RenkuUrl}
import io.renku.jsonld._
import io.renku.jsonld.ontology._

sealed trait Person extends PersonAlgebra with Product with Serializable {
  type Id <: ResourceId
  val resourceId:       Id
  val name:             Name
  val maybeGitLabId:    Option[GitLabId]
  val maybeEmail:       Option[Email]
  val maybeOrcidId:     Option[OrcidId]
  val maybeAffiliation: Option[Affiliation]
}

sealed trait PersonAlgebra {
  def add(gitLabId: GitLabId)(implicit renkuUrl: RenkuUrl): Person.WithGitLabId
}

object Person {

  final case class WithGitLabId(
      resourceId:       ResourceId.GitLabIdBased,
      gitLabId:         GitLabId,
      name:             Name,
      maybeEmail:       Option[Email],
      maybeOrcidId:     Option[OrcidId],
      maybeAffiliation: Option[Affiliation]
  ) extends Person {

    override type Id = ResourceId.GitLabIdBased

    override val maybeGitLabId: Option[GitLabId] = Some(gitLabId)

    override def add(gitLabId: GitLabId)(implicit renkuUrl: RenkuUrl): WithGitLabId =
      copy(resourceId = ResourceId(gitLabId), gitLabId = gitLabId)
  }

  final case class WithEmail(
      resourceId:       ResourceId,
      name:             Name,
      email:            Email,
      maybeOrcidId:     Option[OrcidId],
      maybeAffiliation: Option[Affiliation]
  ) extends Person {
    override type Id = ResourceId

    override val maybeGitLabId: Option[GitLabId] = None
    override val maybeEmail:    Option[Email]    = Some(email)

    override def add(gitLabId: GitLabId)(implicit renkuUrl: RenkuUrl): WithGitLabId =
      Person.WithGitLabId(ResourceId(gitLabId), gitLabId, name, email.some, maybeOrcidId, maybeAffiliation)
  }

  final case class WithNameOnly(
      resourceId:       ResourceId,
      name:             Name,
      maybeOrcidId:     Option[OrcidId],
      maybeAffiliation: Option[Affiliation]
  ) extends Person {
    override type Id = ResourceId

    override val maybeGitLabId: Option[GitLabId] = None
    override val maybeEmail:    Option[Email]    = None

    override def add(gitLabId: GitLabId)(implicit renkuUrl: RenkuUrl): WithGitLabId =
      Person.WithGitLabId(ResourceId(gitLabId), gitLabId, name, maybeEmail = None, maybeOrcidId, maybeAffiliation)
  }

  def from(
      resourceId:       ResourceId,
      name:             Name,
      maybeEmail:       Option[Email] = None,
      maybeGitLabId:    Option[GitLabId] = None,
      maybeOrcid:       Option[OrcidId],
      maybeAffiliation: Option[Affiliation] = None
  ): ValidatedNel[String, Person] = (resourceId, maybeGitLabId, maybeEmail) match {
    case (id: ResourceId.GitLabIdBased, Some(gitLabId), maybeEmail) =>
      Person.WithGitLabId(id, gitLabId, name, maybeEmail, maybeOrcid, maybeAffiliation).validNel
    case (id: ResourceId.EmailBased, None, Some(email)) =>
      Person.WithEmail(id, name, email, maybeOrcid, maybeAffiliation).validNel
    case (id: ResourceId.OrcidIdBased, None, Some(email)) if maybeOrcid.forall(orcid => id.show endsWith orcid.id) =>
      Person.WithEmail(id, name, email, maybeOrcid, maybeAffiliation).validNel
    case (id: ResourceId.NameBased, None, None) =>
      Person.WithNameOnly(id, name, maybeOrcid, maybeAffiliation).validNel
    case (id: ResourceId.OrcidIdBased, None, None) if maybeOrcid.forall(orcid => id.show endsWith orcid.id) =>
      Person.WithNameOnly(id, name, maybeOrcid, maybeAffiliation).validNel
    case _ =>
      show"Invalid Person: $resourceId, name = $name, gitLabId = $maybeGitLabId, orcidId = $maybeOrcid, email = $maybeEmail, affiliation = $maybeAffiliation".invalidNel
  }

  implicit def functions[P <: Person](implicit glApiUrl: GitLabApiUrl): EntityFunctions[P] =
    new EntityFunctions[P] {
      override val findAllPersons: P => Set[Person] = Set(_)

      override val encoder: GraphClass => JsonLDEncoder[P] = Person.encoder(glApiUrl, _)
    }

  import io.renku.graph.model.Schemas._
  import io.renku.jsonld.JsonLDEncoder.encodeOption
  import io.renku.jsonld.syntax._

  val entityTypes: EntityTypes = EntityTypes of (prov / "Person", schema / "Person")

  implicit def encoder[P <: Person](implicit glUrl: GitLabApiUrl, graph: GraphClass): JsonLDEncoder[P] = graph match {
    case GraphClass.Project => JsonLDEncoder.instance(_.resourceId.asEntityId.asJsonLD)
    case _ =>
      JsonLDEncoder.instance {
        case Person.WithGitLabId(id, gitLabId, name, maybeEmail, maybeOrcid, maybeAffiliation) =>
          val sameAsJson = JsonLD.arr(
            List(gitLabId.asJsonLD(gitLabIdEncoder).some, maybeOrcid.map(_.asJsonLD(orcidIdEncoder))).flatten: _*
          )
          JsonLD.entity(
            id.asEntityId,
            entityTypes,
            schema / "email"       -> maybeEmail.asJsonLD,
            schema / "name"        -> name.asJsonLD,
            schema / "sameAs"      -> sameAsJson,
            schema / "affiliation" -> maybeAffiliation.asJsonLD
          )
        case Person.WithEmail(id, name, email, maybeOrcid, maybeAffiliation) =>
          JsonLD.entity(
            id.asEntityId,
            entityTypes,
            schema / "email"       -> email.asJsonLD,
            schema / "name"        -> name.asJsonLD,
            schema / "sameAs"      -> maybeOrcid.asJsonLD(encodeOption(orcidIdEncoder)),
            schema / "affiliation" -> maybeAffiliation.asJsonLD
          )
        case Person.WithNameOnly(id, name, maybeOrcid, maybeAffiliation) =>
          JsonLD.entity(
            id.asEntityId,
            entityTypes,
            schema / "name"        -> name.asJsonLD,
            schema / "sameAs"      -> maybeOrcid.asJsonLD(encodeOption(orcidIdEncoder)),
            schema / "affiliation" -> maybeAffiliation.asJsonLD
          )
      }
  }

  private val sameAsTypes:        EntityTypes = EntityTypes.of(schema / "URL")
  val gitLabSameAsAdditionalType: String      = "GitLab"

  def toGitLabSameAsEntityId(gitLabId: GitLabId)(implicit gitLabApiUrl: GitLabApiUrl): EntityId =
    EntityId of (gitLabApiUrl / "users" / gitLabId).show

  def gitLabIdEncoder(implicit gitLabApiUrl: GitLabApiUrl): JsonLDEncoder[GitLabId] = JsonLDEncoder.instance {
    gitLabId =>
      JsonLD.entity(
        toGitLabSameAsEntityId(gitLabId),
        sameAsTypes,
        schema / "identifier"     -> gitLabId.value.asJsonLD,
        schema / "additionalType" -> gitLabSameAsAdditionalType.asJsonLD
      )
  }

  private val orcidSameAsAdditionalType: String = "Orcid"
  implicit lazy val orcidIdEncoder: JsonLDEncoder[OrcidId] = JsonLDEncoder.instance { orcidId =>
    JsonLD.entity(
      EntityId of orcidId.show,
      sameAsTypes,
      schema / "additionalType" -> orcidSameAsAdditionalType.asJsonLD
    )
  }

  implicit def decoder(implicit renkuUrl: RenkuUrl): JsonLDDecoder[Person] =
    JsonLDDecoder.cacheableEntity(entityTypes) { cursor =>
      import io.circe.DecodingFailure
      import io.renku.graph.model.views.StringTinyTypeJsonLDDecoders._

      def decodeSameAs[T](use: JsonLDDecoder[T], onMultiple: String): Either[DecodingFailure, Option[T]] =
        cursor
          .downField(schema / "sameAs")
          .as(JsonLDDecoder.decodeList(use))
          .leftFlatMap(_ => List.empty.asRight)
          .flatMap(failIfMoreThanOne[T](onMultiple))

      def failIfMoreThanOne[T](error: String): List[T] => Either[DecodingFailure, Option[T]] =
        _.distinct match {
          case Nil           => None.asRight
          case single :: Nil => single.some.asRight
          case list          => DecodingFailure(error + s" $list", Nil).asLeft
        }

      for {
        maybeOrcidAsId   <- cursor.downEntityId.as[OrcidId].map(_.some).leftFlatMap(_ => None.asRight)
        resourceId       <- maybeOrcidAsId.map(ResourceId(_).asRight).getOrElse(cursor.downEntityId.as[ResourceId])
        names            <- cursor.downField(schema / "name").as[List[Name]]
        maybeEmail       <- cursor.downField(schema / "email").as[Option[Email]]
        maybeAffiliation <- cursor.downField(schema / "affiliation").as[List[Affiliation]].map(_.reverse.headOption)
        maybeGitLabId    <- decodeSameAs(use = gitLabIdDecoder, onMultiple = "multiple GitLabId sameAs")
        maybeOrcidId     <- decodeSameAs(use = orcidIdDecoder, onMultiple = "multiple OrcidId sameAs")
        name <- if (names.isEmpty) DecodingFailure(s"No name on Person $resourceId", Nil).asLeft
                else names.reverse.head.asRight
        person <-
          Person
            .from(resourceId, name, maybeEmail, maybeGitLabId, maybeOrcidId orElse maybeOrcidAsId, maybeAffiliation)
            .toEither
            .leftMap(errs => DecodingFailure(errs.nonEmptyIntercalate("; "), Nil))
      } yield person
    }

  private lazy val gitLabIdDecoder: JsonLDDecoder[GitLabId] =
    JsonLDDecoder.entity(sameAsTypes, and(additionalType = gitLabSameAsAdditionalType)) {
      _.downField(schema / "identifier").as[GitLabId]
    }

  private lazy val orcidIdDecoder: JsonLDDecoder[OrcidId] =
    JsonLDDecoder.entity(sameAsTypes, and(additionalType = orcidSameAsAdditionalType)) {
      _.downEntityId.as[OrcidId]
    }

  private def and(additionalType: String): Cursor => io.circe.Decoder.Result[Boolean] =
    _.downField(schema / "additionalType").as[String].map(_ == additionalType)

  object Ontology {

    val typeClass:           Class            = Class(schema / "Person")
    val sameAs:              Property         = schema / "sameAs"
    val nameProperty:        DataProperty.Def = DataProperty(schema / "name", xsd / "string")
    val emailProperty:       DataProperty.Def = DataProperty(schema / "email", xsd / "string")
    val affiliationProperty: DataProperty.Def = DataProperty(schema / "affiliation", xsd / "string")

    lazy val typeDef: Type = {
      val sameAsType = Type.Def(
        Class(schema / "URL"),
        DataProperty(schema / "identifier", xsd / "string", xsd / "int"),
        DataProperty(schema / "additionalType", DataPropertyRange("Orcid", "GitLab"))
      )

      Type.Def(
        typeClass,
        ObjectProperties(ObjectProperty(sameAs, sameAsType)),
        DataProperties(emailProperty, nameProperty, affiliationProperty)
      )
    }
  }

  def fromCli(cli: CliPerson): ValidatedNel[String, Person] = {
    val maybeOrcidId = OrcidId.from(cli.resourceId.value).toOption
    Person.from(cli.resourceId, cli.name, cli.email, None, maybeOrcidId, cli.affiliation)
  }
}
