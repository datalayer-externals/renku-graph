package io.renku.graph.model.cli

import cats.data.NonEmptyList
import io.renku.graph.model.{GraphModelGenerators, InvalidationTime, RenkuUrl, entities}
import io.renku.generators.Generators.Implicits._
import io.renku.graph.model.entities.Dataset.Identification
import io.renku.graph.model.testentities.generators.EntitiesGenerators
import org.scalacheck.Gen

import java.time.{Instant, ZoneOffset}

trait CliGenerators {
  implicit def renkuUrl: RenkuUrl

  val datasetSameAsGen: Gen[CliDatasetSameAs] =
    GraphModelGenerators.datasetSameAs.map(e => CliDatasetSameAs(e.value))

  val datasetProvenanceGen: Gen[CliDatasetProvenance] =
    for {
      resourceId <- GraphModelGenerators.datasetResourceIds
      identifier <- GraphModelGenerators.datasetIdentifiers
      title      <- GraphModelGenerators.datasetTitles
      name       <- GraphModelGenerators.datasetNames
      creator    <- EntitiesGenerators.personEntities
      created    <- GraphModelGenerators.datasetCreatedDates().toGeneratorOfOptions
      published  <- GraphModelGenerators.datasetPublishedDates().toGeneratorOfOptions
      createdOrPublished =
        created
          .map(_.value)
          .orElse(published.map(_.value.atStartOfDay(ZoneOffset.UTC).toInstant))
          .getOrElse(Instant.EPOCH)
      modified <- GraphModelGenerators
                    .datasetModifiedDates(min = createdOrPublished)
                    .toGeneratorOfOptions
      sameAs        <- datasetSameAsGen.toGeneratorOfOptions
      derivedFrom   <- GraphModelGenerators.datasetDerivedFroms.toGeneratorOfOptions
      originalIdent <- GraphModelGenerators.datasetOriginalIdentifiers.toGeneratorOfOptions
      invalidTime   <- GraphModelGenerators.datasetCreatedDates(min = createdOrPublished).toGeneratorOfOptions
    } yield CliDatasetProvenance(
      id = Identification(resourceId, identifier, title, name),
      creators = NonEmptyList.one(creator.to[entities.Person]),
      createdAt = created,
      publishedAt = if (created.isDefined) published else None,
      modifiedAt = modified,
      sameAs = sameAs,
      derivedFrom = derivedFrom,
      originalIdentifier = originalIdent,
      invalidationTime = invalidTime.map(t => InvalidationTime(t.value))
    )
}

object CliGenerators {
  def apply(givenRenkuUrl: RenkuUrl): CliGenerators =
    new CliGenerators {
      implicit override def renkuUrl: RenkuUrl = givenRenkuUrl
    }
}
