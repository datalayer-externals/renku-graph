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

package io.renku.entities.searchgraphs.datasets

import io.renku.graph.model.Schemas.{xsd, _}
import io.renku.graph.model.entities.{Dataset, Person, Project}
import io.renku.jsonld.Property
import io.renku.jsonld.ontology._

object DatasetSearchInfoOntology {

  val slugProperty:                DataProperty.Def = Dataset.Ontology.slugProperty
  val visibilityProperty:          DataProperty.Def = Project.Ontology.visibilityProperty
  val dateCreatedProperty:         DataProperty.Def = Dataset.Ontology.dateCreatedProperty
  val datePublishedProperty:       DataProperty.Def = Dataset.Ontology.datePublishedProperty
  val dateModifiedProperty:        DataProperty.Def = DataProperty(schema / "dateModified", xsd / "dateTime")
  val keywordsConcatProperty:      DataProperty.Def = DataProperty(renku / "keywordsConcat", xsd / "string")
  val descriptionProperty:         DataProperty.Def = Dataset.Ontology.descriptionProperty
  val creatorProperty:             Property         = Dataset.Ontology.creator
  val creatorsNamesConcatProperty: DataProperty.Def = DataProperty(renku / "creatorsNamesConcat", xsd / "string")
  val imagesConcatProperty:        DataProperty.Def = DataProperty(renku / "imagesConcat", xsd / "string")
  val linkProperty:                Property         = renku / "datasetProjectLink"
  val projectsVisibilitiesConcatProperty: DataProperty.Def =
    DataProperty(renku / "projectsVisibilitiesConcat", xsd / "string")

  lazy val typeDef: Type = Type.Def(
    Class(renku / "DiscoverableDataset"),
    ObjectProperties(
      ObjectProperty(creatorProperty, Person.Ontology.typeDef),
      ObjectProperty(linkProperty, LinkOntology.typeDef)
    ),
    DataProperties(
      slugProperty,
      visibilityProperty,
      dateCreatedProperty,
      datePublishedProperty,
      dateModifiedProperty,
      keywordsConcatProperty,
      descriptionProperty,
      creatorsNamesConcatProperty,
      imagesConcatProperty,
      projectsVisibilitiesConcatProperty
    )
  )
}

object LinkOntology {

  val project: Property = renku / "project"
  val dataset: Property = renku / "dataset"

  lazy val typeDef: Type = Type.Def(
    Class(renku / "DatasetProjectLink"),
    ObjectProperties(
      ObjectProperty(project, Project.Ontology.typeDef),
      ObjectProperty(dataset, Dataset.Ontology.typeDef)
    ),
    DataProperties()
  )
}
