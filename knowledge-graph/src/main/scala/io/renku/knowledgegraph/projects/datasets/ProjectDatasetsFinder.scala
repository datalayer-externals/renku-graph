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

package io.renku.knowledgegraph.projects.datasets

import Endpoint.Criteria
import cats.NonEmptyParallel
import cats.effect.Async
import cats.syntax.all._
import io.renku.graph.model.datasets.{DateCreated, DateModified, DatePublished, DerivedFrom, Identifier, Name, OriginalIdentifier, SameAs, Title}
import io.renku.graph.model.images.ImageUri
import io.renku.graph.model.{RenkuUrl, projects}
import io.renku.http.rest.Sorting
import io.renku.http.rest.paging.Paging.PagedResultsFinder
import io.renku.http.rest.paging.{Paging, PagingResponse}
import io.renku.triplesstore.SparqlQuery.Prefixes
import io.renku.triplesstore._
import io.renku.triplesstore.client.model.OrderBy
import io.renku.triplesstore.client.sparql.{Fragment, SparqlEncoder}
import org.typelevel.log4cats.Logger

private trait ProjectDatasetsFinder[F[_]] {
  def findProjectDatasets(criteria: Criteria): F[PagingResponse[ProjectDataset]]
}

private object ProjectDatasetsFinder {

  def apply[F[_]: Async: NonEmptyParallel: Logger: SparqlQueryTimeRecorder](conCfg: ProjectsConnectionConfig)(implicit
      renkuUrl: RenkuUrl
  ): ProjectDatasetsFinder[F] = new ProjectDatasetsFinderImpl[F](TSClient[F](conCfg))
}

private class ProjectDatasetsFinderImpl[F[_]: Async: NonEmptyParallel: Logger: SparqlQueryTimeRecorder](
    tsClient: TSClient[F]
)(implicit renkuUrl: RenkuUrl)
    extends ProjectDatasetsFinder[F]
    with Paging[ProjectDataset] {

  import ResultsDecoder._
  import eu.timepit.refined.auto._
  import io.circe.{Decoder, DecodingFailure}
  import io.renku.graph.model.Schemas._
  import io.renku.jsonld.syntax._
  import io.renku.triplesstore.client.syntax._

  def findProjectDatasets(criteria: Criteria): F[PagingResponse[ProjectDataset]] = {
    implicit val resultsFinder: PagedResultsFinder[F, ProjectDataset] =
      tsClient.pagedResultsFinder(query(criteria))
    findPage[F](criteria.paging)
  }

  private def query(criteria: Criteria) = SparqlQuery.of(
    name = "ds projects",
    Prefixes of (prov -> "prov", renku -> "renku", schema -> "schema", xsd -> "xsd"),
    sparql"""|SELECT ?identifier ?name ?slug
             |  ?modifiedCreatedOrPublished ?maybeDateCreated ?maybeDatePublished ?maybeDateModified
             |  ?topmostSameAs ?maybeDerivedFrom ?originalId
             |  (GROUP_CONCAT(?encodedImageUrl; separator=',') AS ?images)
             |WHERE {
             |   BIND (${projects.ResourceId(criteria.projectSlug).asEntityId} AS ?projectId)
             |   GRAPH ?projectId {
             |     ?projectId renku:hasDataset ?datasetId.
             |     ?datasetId a schema:Dataset;
             |               schema:identifier ?identifier;
             |               schema:name ?name;
             |               renku:slug ?slug;
             |               renku:topmostSameAs ?topmostSameAs;
             |               renku:topmostDerivedFrom ?topmostDerivedFrom.
             |     ?topmostDerivedFrom schema:identifier ?originalId.
             |     OPTIONAL { ?datasetId prov:wasDerivedFrom/schema:url ?maybeDerivedFrom }.
             |     OPTIONAL { ?datasetId schema:dateCreated ?maybeDateModified }
             |     OPTIONAL { ?datasetId schema:datePublished ?maybeDatePublished }
             |     OPTIONAL { ?topmostDerivedFrom schema:dateCreated ?maybeDateCreated }
             |     OPTIONAL { ?topmostDerivedFrom schema:datePublished ?maybeDatePublished }
             |     FILTER NOT EXISTS { ?otherDsId prov:wasDerivedFrom/schema:url ?datasetId }
             |     FILTER NOT EXISTS { ?datasetId prov:invalidatedAtTime ?invalidationTime. }
             |     OPTIONAL {
             |       ?imageId schema:position ?imagePosition ;
             |                schema:contentUrl ?imageUrl ;
             |                ^schema:image ?datasetId .
             |       BIND(CONCAT(STR(?imagePosition), STR(':'), STR(?imageUrl)) AS ?encodedImageUrl)
             |     }
             |
             |     BIND(IF (BOUND(?maybeDateModified), ?maybeDateModified,
             |       IF (BOUND(?maybeDateCreated), ?maybeDateCreated, xsd:dateTime(?maybeDatePublished))) AS ?modifiedCreatedOrPublished
             |     )
             |   }
             |}
             |GROUP BY ?identifier ?name ?slug
             |  ?maybeDateCreated ?maybeDatePublished ?maybeDateModified ?modifiedCreatedOrPublished
             |  ?topmostSameAs ?maybeDerivedFrom ?originalId
             |${`ORDER BY`(criteria.sorting)}
             |""".stripMargin
  )

  private def `ORDER BY`(
      sorting: Sorting[Criteria.Sort.type]
  )(implicit encoder: SparqlEncoder[OrderBy]): Fragment = {
    def mapPropertyName(property: Criteria.Sort.SortProperty) = property match {
      case Criteria.Sort.ByName         => OrderBy.Property("LCASE(?name)")
      case Criteria.Sort.ByDateModified => OrderBy.Property("?modifiedCreatedOrPublished")
    }

    encoder(sorting.toOrderBy(mapPropertyName))
  }

  private implicit val recordDecoder: Decoder[ProjectDataset] = { implicit cur =>
    import io.renku.tinytypes.json.TinyTypeDecoders._

    def sameAsOrDerived(from: SameAs, and: Option[DerivedFrom]): ProjectDataset.SameAsOrDerived = from -> and match {
      case (_, Some(derivedFrom)) => Right(derivedFrom)
      case (sameAs, _)            => Left(sameAs)
    }

    def toListOfImageUrls(urlString: Option[String]): List[ImageUri] =
      urlString
        .map(
          _.split(",")
            .map(_.trim)
            .map { case s"$position:$url" => position.toIntOption.getOrElse(0) -> ImageUri(url) }
            .toSet[(Int, ImageUri)]
            .toList
            .sortBy(_._1)
            .map(_._2)
        )
        .getOrElse(Nil)

    def toCreatedOrPublished(maybeDateCreated:   Option[DateCreated],
                             maybeDatePublished: Option[DatePublished],
                             id:                 Identifier
    ) = (maybeDateCreated orElse maybeDatePublished).toRight(
      DecodingFailure(
        DecodingFailure.Reason.CustomReason(s"Neither date created nor published found for dataset $id"),
        cur
      )
    )

    def toValidatedDateModified(maybeDerivedFrom:  Option[DerivedFrom],
                                maybeDateModified: Option[DateModified],
                                id:                Identifier
    ): Decoder.Result[Option[DateModified]] = maybeDerivedFrom match {
      case None => Option.empty.asRight
      case Some(_) =>
        maybeDateModified match {
          case Some(d) => d.some.asRight
          case None =>
            DecodingFailure(DecodingFailure.Reason.CustomReason(s"No date modified for modified dataset $id"),
                            cur
            ).asLeft
        }
    }

    for {
      id                    <- extract[Identifier]("identifier")
      title                 <- extract[Title]("name")
      name                  <- extract[Name]("slug")
      maybeDateModified     <- extract[Option[DateModified]]("maybeDateModified")
      maybeDateCreated      <- extract[Option[DateCreated]]("maybeDateCreated")
      maybeDatePublished    <- extract[Option[DatePublished]]("maybeDatePublished")
      createdOrPublished    <- toCreatedOrPublished(maybeDateCreated, maybeDatePublished, id)
      sameAs                <- extract[SameAs]("topmostSameAs")
      maybeDerivedFrom      <- extract[Option[DerivedFrom]]("maybeDerivedFrom")
      originalId            <- extract[OriginalIdentifier]("originalId")
      images                <- extract[Option[String]]("images").map(toListOfImageUrls)
      dateModifiedValidated <- toValidatedDateModified(maybeDerivedFrom, maybeDateModified, id)
    } yield ProjectDataset(id,
                           originalId,
                           title,
                           name,
                           createdOrPublished,
                           dateModifiedValidated,
                           sameAsOrDerived(from = sameAs, and = maybeDerivedFrom),
                           images
    )
  }
}
