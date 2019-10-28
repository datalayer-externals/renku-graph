/*
 * Copyright 2019 Swiss Data Science Center (SDSC)
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

package ch.datascience.triplesgenerator.reprovisioning

import cats.data.OptionT
import cats.effect.{ContextShift, IO, Timer}
import ch.datascience.graph.model.SchemaVersion
import ch.datascience.logging.ExecutionTimeRecorder
import ch.datascience.rdfstore.IORdfStoreClient.RdfQuery
import ch.datascience.rdfstore.{IORdfStoreClient, RdfStoreConfig}
import io.chrisdavenport.log4cats.Logger
import io.circe.{Decoder, HCursor}

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

private trait OutdatedTriplesFinder[Interpretation[_]] {
  def findOutdatedTriples: OptionT[Interpretation, OutdatedTriples]
}

private class IOOutdatedTriplesFinder(
    rdfStoreConfig:          RdfStoreConfig,
    executionTimeRecorder:   ExecutionTimeRecorder[IO],
    schemaVersion:           SchemaVersion,
    logger:                  Logger[IO]
)(implicit executionContext: ExecutionContext, contextShift: ContextShift[IO], timer: Timer[IO])
    extends IORdfStoreClient[RdfQuery](rdfStoreConfig, logger)
    with OutdatedTriplesFinder[IO] {

  import executionTimeRecorder._

  override def findOutdatedTriples: OptionT[IO, OutdatedTriples] = OptionT {
    measureExecutionTime {
      queryExpecting[Option[OutdatedTriples]](using = query)
    } map logExecutionTime(withMessage = "Searching for outdated triples finished")
  }

  private val query = s"""
                         |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                         |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                         |PREFIX prov: <http://www.w3.org/ns/prov#>
                         |PREFIX schema: <http://schema.org/>
                         |PREFIX dcterms: <http://purl.org/dc/terms/>
                         |
                         |SELECT DISTINCT ?project ?commit
                         |WHERE {
                         |  # finding a project having an Activity triple with either no agent or agent with a different version
                         |  {
                         |  SELECT DISTINCT ?project
                         |  WHERE {
                         |    {
                         |        ?commit dcterms:isPartOf|schema:isPartOf ?project ;
                         |                rdf:type prov:Activity ;
                         |                prov:agent ?agent .
                         |        ?agent  rdf:type prov:SoftwareAgent ;
                         |                rdfs:label ?version .
                         |        FILTER (?version != "renku $schemaVersion")
                         |    }
                         |    UNION
                         |    {
                         |        ?commit dcterms:isPartOf|schema:isPartOf ?project ;
                         |                rdf:type prov:Activity .
                         |        FILTER NOT EXISTS {
                         |          ?commit prov:agent ?agent .
                         |          ?agent  rdf:type prov:SoftwareAgent .
                         |        }
                         |    }
                         |  }
                         |  LIMIT 1
                         |  }
                         |  # finding all the commits for the found project with either no agent or agent with a different version
                         |  {
                         |	  ?commit dcterms:isPartOf|schema:isPartOf ?project ;
                         |            rdf:type prov:Activity ;
                         |            prov:agent ?agent .
                         |    ?agent  rdf:type prov:SoftwareAgent ;
                         |            rdfs:label ?version .
                         |    FILTER (?version != "renku $schemaVersion")
                         |  }
                         |  UNION
                         |  {
                         |	  ?commit dcterms:isPartOf|schema:isPartOf ?project ;
                         |            rdf:type prov:Activity .
                         |    FILTER NOT EXISTS {
                         |      ?commit prov:agent ?agent .
                         |      ?agent  rdf:type prov:SoftwareAgent .
                         |    }
                         |  }
                         |}
                         |""".stripMargin

  private implicit lazy val outdatedTriplesDecoder: Decoder[Option[OutdatedTriples]] =
    _.downField("results")
      .downField("bindings")
      .as[List[(ProjectResource, CommitIdResource)]]
      .map(toMaybeOutdatedTriples)

  private implicit lazy val jsonDecoder: Decoder[(ProjectResource, CommitIdResource)] = (cursor: HCursor) => {
    for {
      maybeProjectResource <- cursor.downField("project").downField("value").as[ProjectResource]
      maybeCommitId        <- cursor.downField("commit").downField("value").as[CommitIdResource]
    } yield maybeProjectResource -> maybeCommitId
  }

  private lazy val toMaybeOutdatedTriples: List[(ProjectResource, CommitIdResource)] => Option[OutdatedTriples] = {
    case Nil => None
    case (projectResource, commitId) +: tail =>
      Some {
        OutdatedTriples(
          projectResource,
          commits = tail.foldLeft(Set(commitId)) {
            case (allCommits, (projectResource, commit)) => allCommits + commit
          }
        )
      }
  }
}
