package io.renku.cli.model

import io.renku.cli.model.Ontologies.{Renku, Schema}
import io.renku.graph.model.commandParameters._
import io.renku.jsonld.syntax._
import io.renku.jsonld.{EntityTypes, JsonLD, JsonLDDecoder}

final case class CliCommandOutput(
    resourceId:     ResourceId,
    name:           Name,
    description:    Option[Description],
    prefix:         Option[Prefix],
    position:       Option[Position],
    defaultValue:   ParameterDefaultValue,
    mappedTo:       Option[CliMappedIOStream],
    encodingFormat: Option[EncodingFormat],
    createFolder:   FolderCreation
) extends CliCommandParameterBase

object CliCommandOutput {
  private val entityTypes: EntityTypes = EntityTypes of (Renku.CommandOutput, Renku.CommandParameterBase)

  private[model] def matchingEntityTypes(entityTypes: EntityTypes): Boolean =
    entityTypes == this.entityTypes

  implicit val jsonLDDecoder: JsonLDDecoder[CliCommandOutput] = JsonLDDecoder.entity(entityTypes) { cursor =>
    for {
      resourceId       <- cursor.downEntityId.as[ResourceId]
      position         <- cursor.downField(Renku.position).as[Option[Position]]
      name             <- cursor.downField(Schema.name).as[Name]
      maybeDescription <- cursor.downField(Schema.description).as[Option[Description]]
      maybePrefix      <- cursor.downField(Renku.prefix).as[Option[Prefix]]
      defaultValue     <- cursor.downField(Schema.defaultValue).as[ParameterDefaultValue]
      encodingFormat   <- cursor.downField(Schema.encodingFormat).as[Option[EncodingFormat]]
      mappedTo         <- cursor.downField(Renku.mappedTo).as[Option[CliMappedIOStream]]
      createFolder     <- cursor.downField(Renku.createFolder).as[FolderCreation]
    } yield CliCommandOutput(
      resourceId,
      name,
      maybeDescription,
      maybePrefix,
      position,
      defaultValue,
      mappedTo,
      encodingFormat,
      createFolder
    )
  }

  implicit val jsonLDEncoder: FlatJsonLDEncoder[CliCommandOutput] =
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
        Renku.mappedTo        -> param.mappedTo.asJsonLD,
        Renku.createFolder    -> param.createFolder.asJsonLD
      )
    }
}
