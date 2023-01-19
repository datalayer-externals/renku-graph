package io.renku.cli.model

import io.renku.graph.model.commandParameters._
import io.renku.jsonld.syntax._
import io.renku.jsonld.{EntityTypes, JsonLD, JsonLDDecoder}
import Ontologies.{Renku, Schema}

final case class CliCommandInput(
    resourceId:     ResourceId,
    name:           Name,
    description:    Option[Description],
    prefix:         Option[Prefix],
    position:       Option[Position],
    defaultValue:   ParameterDefaultValue,
    mappedTo:       Option[CliMappedIOStream],
    encodingFormat: Option[EncodingFormat]
) extends CliCommandParameterBase

object CliCommandInput {
  private val entityTypes: EntityTypes = EntityTypes of (Renku.CommandInput, Renku.CommandParameterBase)

  private[model] def matchingEntityTypes(entityTypes: EntityTypes): Boolean =
    entityTypes == this.entityTypes

  implicit val jsonLDDecoder: JsonLDDecoder[CliCommandInput] =
    JsonLDDecoder.entity(entityTypes, _.getEntityTypes.map(matchingEntityTypes)) { cursor =>
      for {
        resourceId       <- cursor.downEntityId.as[ResourceId]
        position         <- cursor.downField(Renku.position).as[Option[Position]]
        name             <- cursor.downField(Schema.name).as[Name]
        maybeDescription <- cursor.downField(Schema.description).as[Option[Description]]
        maybePrefix      <- cursor.downField(Renku.prefix).as[Option[Prefix]]
        defaultValue     <- cursor.downField(Schema.defaultValue).as[ParameterDefaultValue]
        encodingFormat   <- cursor.downField(Schema.encodingFormat).as[Option[EncodingFormat]]
        mappedTo         <- cursor.downField(Renku.mappedTo).as[Option[CliMappedIOStream]]

        // mappedToId <- cursor.downField(Renku.mappedTo).as[EntityId]
        // mappedTo <- cursor.focusTop.as[List[CliMappedIOStream]].map(_.filter(_.id.asEntityId == mappedToId))

        // mappedTo <- cursor.decodeLinked[CliMappedIOStream](Renku.mappedTo)(_.id.asEntityId)
        _ <- Right(())
      } yield CliCommandInput(
        resourceId,
        name,
        maybeDescription,
        maybePrefix,
        position,
        defaultValue,
        mappedTo,
        encodingFormat
      )
    }

  implicit val jsonLDEncoder: FlatJsonLDEncoder[CliCommandInput] =
    FlatJsonLDEncoder.unsafe { param =>
      JsonLD.entity(
        param.resourceId.asEntityId,
        entityTypes,
        Renku.position        -> param.position.asJsonLD,
        Schema.name           -> param.name.asJsonLD,
        Schema.description    -> param.description.asJsonLD,
        Renku.prefix          -> param.prefix.asJsonLD,
        Schema.defaultValue   -> param.defaultValue.asJsonLD,
        Schema.encodingFormat -> param.encodingFormat.asJsonLD,
        Renku.mappedTo        -> param.mappedTo.asJsonLD
      )
    }
}
