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

import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.all._
import io.renku.cli.model.CliPerson
import io.renku.graph.model._
import io.renku.graph.model.persons.{Affiliation, Email, GitLabId, Name, OrcidId, ResourceId}
import io.renku.jsonld.{EntityId, EntityTypes, JsonLD, JsonLDEncoder, Property}
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

  def apply(name: Name, gitLabId: GitLabId)(implicit renkuUrl: RenkuUrl): Person.WithGitLabId =
    Person.WithGitLabId(persons.ResourceId(gitLabId),
                        gitLabId,
                        name,
                        maybeEmail = None,
                        maybeOrcidId = None,
                        maybeAffiliation = None
    )

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

  def fromCli(cli: CliPerson)(implicit renkuUrl: RenkuUrl): ValidatedNel[String, Person] = {

    val maybeOrcidId         = OrcidId.from(cli.resourceId.value).toOption
    val maybeOrcidResourceId = maybeOrcidId.map(persons.ResourceId(_)).map(_.asRight[NonEmptyList[String]])

    val cliResourceId = maybeOrcidResourceId getOrElse {
      persons.ResourceId.from(cli.resourceId.value).leftMap(err => NonEmptyList.one(err.getMessage))
    }

    cliResourceId
      .flatMap(Person.from(_, cli.name, cli.email, None, maybeOrcidId, cli.affiliation).toEither)
      .toValidated
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
}
