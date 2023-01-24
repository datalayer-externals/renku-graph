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

package io.renku.graph.model.cli

import cats.syntax.all._
import io.renku.cli.model._
import io.renku.graph.model.{datasets, entities}

/** Conversion functions for production model entities into cli entities. */
trait CliConverters {

  def from(person: entities.Person): CliPerson = {
    person.maybeGitLabId.map(_ => throw new Exception(s"Cannot convert Person with GitLabId to CliPerson"))
    val resourceId = person.maybeOrcidId
      .map(id => CliPersonResourceId(id.show))
      .getOrElse(CliPersonResourceId(person.resourceId.value))
    CliPerson(resourceId, person.name, person.maybeEmail, person.maybeAffiliation)
  }

  def from(dataset: entities.Dataset[entities.Dataset.Provenance]): CliDataset =
    CliDataset(
      resourceId = dataset.identification.resourceId,
      identifier = dataset.identification.identifier,
      title = dataset.identification.title,
      name = dataset.identification.name,
      createdOrPublished = dataset.provenance.date,
      dateModified = dataset.provenance match {
        case p: entities.Dataset.Provenance.Modified => datasets.DateModified(p.date).some
        case _ => None
      },
      creators = dataset.provenance.creators.map(from),
      description = dataset.additionalInfo.maybeDescription,
      keywords = dataset.additionalInfo.keywords,
      images = dataset.additionalInfo.images,
      license = dataset.additionalInfo.maybeLicense,
      version = dataset.additionalInfo.maybeVersion,
      datasetFiles = dataset.parts.map(from),
      sameAs = dataset.provenance match {
        case p: entities.Dataset.Provenance.ImportedExternal => p.sameAs.some
        case p: entities.Dataset.Provenance.ImportedInternal => p.sameAs.some
        case _: entities.Dataset.Provenance.Internal         => None
        case _: entities.Dataset.Provenance.Modified         => None
      },
      derivedFrom = dataset.provenance match {
        case m: entities.Dataset.Provenance.Modified => m.derivedFrom.some
        case _ => None
      },
      originalIdentifier = dataset.provenance.originalIdentifier.some,
      invalidationTime = dataset.provenance match {
        case m: entities.Dataset.Provenance.Modified => m.maybeInvalidationTime
        case _ => None
      },
      dataset.publicationEvents.map(from)
    )

  def from(part: entities.DatasetPart): CliDatasetFile =
    CliDatasetFile(part.resourceId,
                   part.external,
                   from(part.entity),
                   part.dateCreated,
                   part.maybeSource,
                   part.maybeInvalidationTime
    )

  def from(pe: entities.PublicationEvent): CliPublicationEvent =
    CliPublicationEvent(pe.resourceId, pe.about, pe.datasetResourceId, pe.maybeDescription, pe.name, pe.startDate)

  def from(entity: entities.Entity): CliSingleEntity = entity match {
    case entities.Entity.InputEntity(id, location, checksum) =>
      CliSingleEntity(id, EntityPath(location.value), checksum, generationIds = Nil)
    case entities.Entity.OutputEntity(id, location, checksum, generationIds) =>
      CliSingleEntity(id, EntityPath(location.value), checksum, generationIds)
  }
}

object CliConverters extends CliConverters
