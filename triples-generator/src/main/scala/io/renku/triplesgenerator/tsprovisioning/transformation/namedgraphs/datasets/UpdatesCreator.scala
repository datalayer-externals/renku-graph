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

package io.renku.triplesgenerator.tsprovisioning.transformation.namedgraphs.datasets

import cats.syntax.all._
import eu.timepit.refined.auto._
import io.renku.graph.model.Schemas._
import io.renku.graph.model.datasets.{DateCreated, Description, OriginalIdentifier, ResourceId, SameAs, TopmostSameAs}
import io.renku.graph.model.entities.Dataset
import io.renku.graph.model.entities.Dataset.Provenance
import io.renku.graph.model.views.RdfResource
import io.renku.graph.model.{GraphClass, persons, projects}
import io.renku.jsonld.syntax._
import io.renku.triplesstore.SparqlQuery
import io.renku.triplesstore.SparqlQuery.Prefixes
import io.renku.triplesstore.client.syntax._

private trait UpdatesCreator {

  def prepareUpdatesWhenInvalidated(dataset: Dataset[Dataset.Provenance.Internal])(implicit
      ev: Dataset.Provenance.Internal.type
  ): List[SparqlQuery]

  def prepareUpdatesWhenInvalidated(projectId: projects.ResourceId,
                                    dataset:   Dataset[Dataset.Provenance.ImportedExternal]
  )(implicit ev: Dataset.Provenance.ImportedExternal.type): List[SparqlQuery]

  def prepareUpdatesWhenInvalidated(projectId: projects.ResourceId,
                                    dataset:   Dataset[Dataset.Provenance.ImportedInternal]
  )(implicit ev: Dataset.Provenance.ImportedInternal.type): List[SparqlQuery]

  def prepareUpdates(dataset:                Dataset[Dataset.Provenance.ImportedInternal],
                     maybeKGTopmostSameAses: Set[TopmostSameAs]
  ): List[SparqlQuery]

  def prepareTopmostSameAsCleanup(projectId:                projects.ResourceId,
                                  dataset:                  Dataset[Dataset.Provenance.ImportedInternal],
                                  maybeParentTopmostSameAs: Option[TopmostSameAs]
  ): List[SparqlQuery]

  def queriesUnlinkingCreators(projectId:    projects.ResourceId,
                               dataset:      Dataset[Dataset.Provenance],
                               creatorsInKG: Set[persons.ResourceId]
  ): List[SparqlQuery]

  def deleteOtherDerivedFrom(projectId: projects.ResourceId,
                             dataset:   Dataset[Dataset.Provenance.Modified]
  ): List[SparqlQuery]

  def deleteOtherTopmostDerivedFrom(projectId: projects.ResourceId,
                                    dataset:   Dataset[Dataset.Provenance.Modified]
  ): List[SparqlQuery]

  def removeOtherOriginalIdentifiers(projectId:               projects.ResourceId,
                                     dataset:                 Dataset[Dataset.Provenance],
                                     originalIdentifiersInKG: Set[OriginalIdentifier]
  ): List[SparqlQuery]

  def removeOtherDateCreated(projectId:       projects.ResourceId,
                             dataset:         Dataset[Dataset.Provenance],
                             dateCreatedInKG: Set[DateCreated]
  ): List[SparqlQuery]

  def removeOtherDescriptions(projectId: projects.ResourceId,
                              dataset:   Dataset[Dataset.Provenance],
                              descsInKG: Set[Description]
  ): List[SparqlQuery]

  def removeOtherSameAs(projectId:  projects.ResourceId,
                        dataset:    Dataset[Dataset.Provenance],
                        sameAsInKG: Set[SameAs]
  ): List[SparqlQuery]

  def deletePublicationEvents(projectId: projects.ResourceId, dataset: Dataset[Dataset.Provenance]): List[SparqlQuery]
}

private object UpdatesCreator extends UpdatesCreator {

  override def prepareUpdatesWhenInvalidated(dataset: Dataset[Dataset.Provenance.Internal])(implicit
      ev: Dataset.Provenance.Internal.type
  ): List[SparqlQuery] =
    List(useTopmostSameAsFromTheOldestDeletedDSChildOnAncestors(dataset), deleteSameAs(dataset))

  override def prepareUpdatesWhenInvalidated(
      projectId: projects.ResourceId,
      dataset:   Dataset[Dataset.Provenance.ImportedExternal]
  )(implicit ev: Dataset.Provenance.ImportedExternal.type): List[SparqlQuery] =
    List(useDeletedDSSameAsAsChildSameAs(projectId, dataset))

  override def prepareUpdatesWhenInvalidated(
      projectId: projects.ResourceId,
      dataset:   Dataset[Dataset.Provenance.ImportedInternal]
  )(implicit ev: Dataset.Provenance.ImportedInternal.type): List[SparqlQuery] =
    List(useDeletedDSSameAsAsChildSameAs(projectId, dataset))

  override def prepareUpdates(dataset:                Dataset[Provenance.ImportedInternal],
                              maybeKGTopmostSameAses: Set[TopmostSameAs]
  ): List[SparqlQuery] = Option
    .when(!(maybeKGTopmostSameAses equals Set(dataset.provenance.topmostSameAs)))(
      SparqlQuery.of(
        name = "transformation - topmostSameAs update",
        Prefixes of (renku -> "renku", schema -> "schema"),
        s"""|DELETE { GRAPH ?g { ?dsId renku:topmostSameAs ?oldTopmost } }
            |INSERT { GRAPH ?g { ?dsId renku:topmostSameAs <${dataset.provenance.topmostSameAs}> } }
            |WHERE {
            |  GRAPH ?g {
            |    ?dsId a schema:Dataset;
            |          renku:topmostSameAs <${dataset.resourceId}>;
            |          renku:topmostSameAs ?oldTopmost.
            |  }
            |}
            |""".stripMargin
      )
    )
    .toList

  override def prepareTopmostSameAsCleanup(projectId:                projects.ResourceId,
                                           dataset:                  Dataset[Dataset.Provenance.ImportedInternal],
                                           maybeParentTopmostSameAs: Option[TopmostSameAs]
  ): List[SparqlQuery] = maybeParentTopmostSameAs match {
    case None    => Nil
    case Some(_) => List(prepareTopmostSameAsCleanUp(projectId, dataset.resourceId, dataset.provenance.topmostSameAs))
  }

  override def queriesUnlinkingCreators(projectId:    projects.ResourceId,
                                        dataset:      Dataset[Dataset.Provenance],
                                        creatorsInKG: Set[persons.ResourceId]
  ): List[SparqlQuery] = {
    val dsCreators = dataset.provenance.creators.map(_.resourceId).toList.toSet
    Option
      .when(dsCreators != creatorsInKG) {
        SparqlQuery.of(
          name = "transformation - delete ds creators link",
          Prefixes of schema -> "schema",
          s"""|DELETE {
              |  GRAPH <${GraphClass.Project.id(projectId)}> { 
              |    ${dataset.resourceId.showAs[RdfResource]} schema:creator ?personId 
              |  }
              |}
              |WHERE {
              |  GRAPH <${GraphClass.Project.id(projectId)}> {
              |    ${dataset.resourceId.showAs[RdfResource]} a schema:Dataset;
              |                                              schema:creator ?personId
              |  }
              |  FILTER (?personId NOT IN (${dsCreators.map(_.showAs[RdfResource]).mkString(", ")}))
              |}
              |""".stripMargin
        )
      }
      .toList
  }

  override def deleteOtherDerivedFrom(projectId: projects.ResourceId,
                                      dataset:   Dataset[Dataset.Provenance.Modified]
  ): List[SparqlQuery] = List(
    SparqlQuery.of(
      name = "transformation - delete other derivedFrom",
      Prefixes of (prov -> "prov", schema -> "schema"),
      s"""|DELETE { GRAPH <${GraphClass.Project.id(projectId)}> { ?dsId prov:wasDerivedFrom ?derivedId } }
          |WHERE {
          |  BIND (${dataset.resourceId.showAs[RdfResource]} AS ?dsId)
          |  GRAPH <${GraphClass.Project.id(projectId)}> {
          |    ?dsId a schema:Dataset;
          |          prov:wasDerivedFrom ?derivedId.
          |    ?derivedId schema:url ?derived.
          |  }
          |  FILTER (?derived != ${dataset.provenance.derivedFrom.showAs[RdfResource]})
          |}
          |""".stripMargin
    )
  )

  override def deleteOtherTopmostDerivedFrom(projectId: projects.ResourceId,
                                             dataset:   Dataset[Dataset.Provenance.Modified]
  ): List[SparqlQuery] = List(
    SparqlQuery.of(
      name = "transformation - delete other topmostDerivedFrom",
      Prefixes of (renku -> "renku", schema -> "schema"),
      s"""|DELETE { GRAPH <${GraphClass.Project.id(projectId)}> { ?dsId renku:topmostDerivedFrom ?topmostDerived } }
          |WHERE {
          |  BIND (${dataset.resourceId.showAs[RdfResource]} AS ?dsId)
          |  GRAPH <${GraphClass.Project.id(projectId)}> {
          |    ?dsId a schema:Dataset;
          |          renku:topmostDerivedFrom ?topmostDerived
          |  }
          |  FILTER (?topmostDerived != ${dataset.provenance.topmostDerivedFrom.showAs[RdfResource]})
          |}
          |""".stripMargin
    )
  )

  private def deleteSameAs(dataset: Dataset[Provenance.Internal]) = SparqlQuery.of(
    name = "transformation - delete sameAs",
    Prefixes of schema -> "schema",
    s"""|DELETE {
        |  GRAPH ?g {
        |    ?dsId schema:sameAs ?sameAs.
        |    ?sameAs ?sameAsPredicate ?sameAsObject.
        |  }
        |}
        |WHERE {
        |  GRAPH ?g {
        |    ?dsId a schema:Dataset;
        |          schema:sameAs ?sameAs.
        |    ?sameAs schema:url <${dataset.resourceId}>;
        |            ?sameAsPredicate ?sameAsObject.
        |  }
        |}
        |""".stripMargin
  )

  private def useTopmostSameAsFromTheOldestDeletedDSChildOnAncestors(dataset: Dataset[Provenance.Internal]) =
    SparqlQuery.of(
      name = "transformation - topmostSameAs from child",
      Prefixes of (renku -> "renku", schema -> "schema", xsd -> "xsd"),
      s"""|DELETE { 
          |  GRAPH ?gg { ?ancestorsDsId renku:topmostSameAs ?deletedDsId }
          |}
          |INSERT {
          |  GRAPH ?gg { ?ancestorsDsId renku:topmostSameAs ?oldestChildResourceId }
          |}
          |WHERE {
          |  BIND (<${dataset.resourceId}> AS ?deletedDsId)
          |  {
          |    SELECT (?dsId AS ?oldestChildResourceId)
          |    WHERE {
          |      GRAPH ?g {
          |        ?dsId a schema:Dataset;
          |              schema:sameAs/schema:url ?deletedDsId;
          |              schema:dateCreated ?date.
          |      }
          |    }
          |    ORDER BY xsd:datetime(?date)
          |    LIMIT 1
          |  }
          |  GRAPH ?gg {
          |    ?ancestorsDsId a schema:Dataset;
          |                   renku:topmostSameAs ?deletedDsId.
          |    FILTER NOT EXISTS { ?ancestorsDsId renku:topmostSameAs ?ancestorsDsId }
          |  }
          |}
          |""".stripMargin
    )

  private def useDeletedDSSameAsAsChildSameAs(projectId: projects.ResourceId, dataset: Dataset[Provenance]) =
    SparqlQuery.of(
      name = "transformation - deleted sameAs as child sameAS",
      Prefixes of (renku -> "renku", schema -> "schema"),
      s"""|DELETE {
          |  GRAPH ?g {
          |    ?dsId schema:sameAs ?sameAs.
          |    ?sameAs ?sameAsPredicate ?sameAsObject.
          |  }
          |}
          |INSERT {
          |  GRAPH ?g {
          |    ?dsId schema:sameAs ?sameAsOnDeletedDS.
          |    ?sameAsOnDeletedDS ?sameAsOnDeletedDSPredicate ?sameAsOnDeletedDSObject.
          |  }
          |}
          |WHERE {
          |  BIND (<${dataset.resourceId}> AS ?deletedDsId)
          |  GRAPH <${GraphClass.Project.id(projectId)}> {
          |    ?deletedDsId a schema:Dataset;
          |                 schema:sameAs ?sameAsOnDeletedDS.
          |    ?sameAsOnDeletedDS ?sameAsOnDeletedDSPredicate ?sameAsOnDeletedDSObject.
          |  }
          |  GRAPH ?g {
          |    ?sameAs schema:url ?deletedDsId;
          |            ?sameAsPredicate ?sameAsObject.
          |    ?dsId a schema:Dataset;
          |          schema:sameAs ?sameAs.
          |  }
          |  FILTER (?g != <${GraphClass.Project.id(projectId)}>)
          |}
          |""".stripMargin
    )

  private def prepareTopmostSameAsCleanUp(projectId:          projects.ResourceId,
                                          dsId:               ResourceId,
                                          modelTopmostSameAs: TopmostSameAs
  ) = SparqlQuery.of(
    name = "transformation - topmostSameAs clean-up",
    Prefixes.of(renku -> "renku", schema -> "schema"),
    s"""|DELETE { GRAPH <${GraphClass.Project.id(projectId)}> { <$dsId> renku:topmostSameAs <$modelTopmostSameAs> } }
        |WHERE { 
        |  {
        |    SELECT (COUNT(?topmost) AS ?count)
        |    WHERE { 
        |      GRAPH <${GraphClass.Project.id(projectId)}> {
        |        <$dsId> renku:topmostSameAs ?topmost
        |      }
        |    }
        |  }
        |  FILTER (?count > 1)
        |}
        |""".stripMargin
  )

  override def removeOtherOriginalIdentifiers(projectId:               projects.ResourceId,
                                              ds:                      Dataset[Provenance],
                                              originalIdentifiersInKG: Set[OriginalIdentifier]
  ): List[SparqlQuery] = Option
    .when((originalIdentifiersInKG - ds.provenance.originalIdentifier).nonEmpty) {
      SparqlQuery.of(
        name = "transformation - originalIdentifier clean-up",
        Prefixes of renku -> "renku",
        s"""|DELETE { GRAPH <${GraphClass.Project.id(projectId)}> { ?dsId renku:originalIdentifier ?origIdentifier } }
            |WHERE {
            |  BIND (${ds.resourceId.showAs[RdfResource]} AS ?dsId)
            |  GRAPH <${GraphClass.Project.id(projectId)}> {
            |    ?dsId renku:originalIdentifier ?origIdentifier
            |  }
            |  FILTER ( ?origIdentifier != '${ds.provenance.originalIdentifier}' )
            |}""".stripMargin
      )
    }
    .toList

  override def removeOtherDateCreated(projectId:       projects.ResourceId,
                                      ds:              Dataset[Dataset.Provenance],
                                      dateCreatedInKG: Set[DateCreated]
  ): List[SparqlQuery] = Option
    .when(
      ds.provenance.date.isInstanceOf[DateCreated] &&
        (dateCreatedInKG - ds.provenance.date.asInstanceOf[DateCreated]).nonEmpty
    ) {
      SparqlQuery.of(
        name = "transformation - dateCreated clean-up",
        Prefixes.of(schema -> "schema", xsd -> "xsd"),
        s"""|DELETE { GRAPH <${GraphClass.Project.id(projectId)}> { ?dsId schema:dateCreated ?date } }
            |WHERE {
            |  BIND (${ds.resourceId.showAs[RdfResource]} AS ?dsId)
            |  GRAPH <${GraphClass.Project.id(projectId)}> {
            |    ?dsId schema:dateCreated ?date
            |  }
            |  BIND (xsd:dateTime('${ds.provenance.date.instant}') AS ?xsdDate)
            |  FILTER ( ?date != ?xsdDate )
            |}""".stripMargin
      )
    }
    .toList

  override def removeOtherDescriptions(projectId: projects.ResourceId,
                                       ds:        Dataset[Dataset.Provenance],
                                       descsInKG: Set[Description]
  ): List[SparqlQuery] =
    ds.additionalInfo.maybeDescription match {
      case Some(desc) if (descsInKG - desc).nonEmpty =>
        List(
          SparqlQuery.of(
            name = "transformation - ds desc clean-up",
            Prefixes of schema -> "schema",
            s"""|DELETE { GRAPH <${GraphClass.Project.id(projectId)}> { ?dsId schema:description ?desc } }
                |WHERE {
                |  BIND (${ds.resourceId.showAs[RdfResource]} AS ?dsId)
                |  GRAPH <${GraphClass.Project.id(projectId)}> {
                |    ?dsId schema:description ?desc
                |  }
                |  FILTER ( ?desc != '$desc' )
                |}""".stripMargin
          )
        )
      case None if descsInKG.nonEmpty =>
        List(
          SparqlQuery.of(
            name = "transformation - ds desc remove",
            Prefixes of schema -> "schema",
            s"""|DELETE { GRAPH <${GraphClass.Project.id(projectId)}> { ?dsId schema:description ?desc } }
                |WHERE {
                |  BIND (${ds.resourceId.showAs[RdfResource]} AS ?dsId)
                |  GRAPH <${GraphClass.Project.id(projectId)}> { ?dsId schema:description ?desc }
                |}""".stripMargin
          )
        )
      case _ => List.empty
    }

  override def removeOtherSameAs(projectId:  projects.ResourceId,
                                 ds:         Dataset[Dataset.Provenance],
                                 sameAsInKG: Set[SameAs]
  ): List[SparqlQuery] = {
    val maybeSameAs = {
      ds.provenance match {
        case p: Dataset.Provenance.ImportedExternal => p.sameAs.some
        case p: Dataset.Provenance.ImportedInternal => p.sameAs.some
        case _ => None
      }
    }.widen[SameAs] >>= (_.asJsonLD.entityId)

    maybeSameAs match {
      case None => Nil
      case Some(dsSameAs) =>
        Option
          .when((sameAsInKG.map(_.show) - dsSameAs.show).nonEmpty) {
            SparqlQuery.of(
              name = "transformation - sameAs clean-up",
              Prefixes of schema -> "schema",
              sparql"""|DELETE { GRAPH ${GraphClass.Project.id(projectId)} { ?dsId schema:sameAs ?sameAs } }
                       |WHERE {
                       |  BIND (${ds.resourceId.asEntityId} AS ?dsId)
                       |  GRAPH ${GraphClass.Project.id(projectId)} { ?dsId schema:sameAs ?sameAs }
                       |  FILTER ( ?sameAs != $dsSameAs )
                       |}""".stripMargin
            )
          }
          .toList
    }
  }

  override def deletePublicationEvents(projectId: projects.ResourceId,
                                       ds:        Dataset[Dataset.Provenance]
  ): List[SparqlQuery] = List(
    SparqlQuery.of(
      name = "transformation - publication event clean-up",
      Prefixes of schema -> "schema",
      sparql"""|DELETE {
               |  GRAPH ${GraphClass.Project.id(projectId)} {
               |    ?about ?aboutP ?aboutO.
               |    ?peId ?peP ?peO.
               |  }
               |}
               |WHERE {
               |  GRAPH ${GraphClass.Project.id(projectId)} {
               |    ?about schema:url ${ds.resourceId.asEntityId};
               |           ?aboutP ?aboutO.
               |    ?peId a schema:PublicationEvent;
               |          schema:about ?about;
               |          ?peP ?peO
               |  }
               |}""".stripMargin
    )
  )
}
