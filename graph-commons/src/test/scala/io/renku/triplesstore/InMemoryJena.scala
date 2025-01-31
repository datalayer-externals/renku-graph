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

package io.renku.triplesstore

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all._
import com.dimafeng.testcontainers.SingleContainer
import eu.timepit.refined.auto._
import io.circe.{Decoder, HCursor, Json}
import io.renku.graph.model._
import io.renku.graph.model.entities.{EntityFunctions, Person}
import io.renku.graph.triplesstore.DatasetTTLs._
import io.renku.http.client._
import io.renku.interpreters.TestLogger
import io.renku.jsonld.JsonLD.{JsonLDArray, JsonLDEntityLike}
import io.renku.jsonld._
import io.renku.logging.TestSparqlQueryTimeRecorder
import io.renku.triplesstore.client.model.{Quad, Triple}
import io.renku.triplesstore.client.syntax._
import io.renku.triplesstore.client.util.{JenaContainer, JenaRunMode}

import scala.collection.mutable
import scala.language.reflectiveCalls

trait InMemoryJena {

  protected val jenaRunMode: JenaRunMode = JenaRunMode.GenericContainer

  private val adminCredentials = BasicAuthCredentials(BasicAuthUsername("admin"), BasicAuthPassword("admin"))

  lazy val container: SingleContainer[_] = JenaContainer.create(jenaRunMode)

  lazy val fusekiUrl: FusekiUrl = FusekiUrl(JenaContainer.fusekiUrl(jenaRunMode, container))

  private val datasets: mutable.Map[FusekiUrl => DatasetConnectionConfig, DatasetConfigFile] = mutable.Map.empty

  protected def registerDataset(connectionInfoFactory: FusekiUrl => DatasetConnectionConfig,
                                maybeConfigFile:       Either[Exception, DatasetConfigFile]
  ): Unit = maybeConfigFile
    .map(configFile => datasets.addOne(connectionInfoFactory -> configFile))
    .fold(throw _, _ => ())

  protected def createDatasets(): IO[Unit] =
    datasets
      .map { case (_, configFile) => datasetsCreator.createDataset(configFile) }
      .toList
      .sequence
      .void

  def clearAllDatasets()(implicit ioRuntime: IORuntime): Unit =
    datasets
      .map { case (connectionInfoFactory, _) => connectionInfoFactory(fusekiUrl).datasetName }
      .foreach(clear)

  def clearIO(datasetName: DatasetName): IO[Unit] =
    queryRunnerFor(datasetName) >>= {
      _.runUpdate(
        SparqlQuery.of("delete all data", "CLEAR ALL")
      )
    }

  def clear(dataset: DatasetName)(implicit ioRuntime: IORuntime): Unit =
    clearIO(dataset).unsafeRunSync()

  def uploadIO(to: DatasetName, graphs: Graph*): IO[Unit] =
    graphs
      .map(_.flatten.fold(throw _, identity))
      .toList
      .map(g => queryRunnerFor(to).flatMap(_.uploadPayload(g)))
      .sequence
      .void

  def uploadIO[T](to: DatasetName, objects: T*)(implicit ef: EntityFunctions[T], gp: GraphsProducer[T]): IO[Unit] =
    uploadIO(to, objects >>= gp.apply: _*)

  def upload(to: DatasetName, graphs: Graph*)(implicit ioRuntime: IORuntime): Unit =
    uploadIO(to, graphs: _*).unsafeRunSync()

  def upload[T](to: DatasetName, objects: T*)(implicit
      entityFunctions: EntityFunctions[T],
      graphsProducer:  GraphsProducer[T],
      ioRuntime:       IORuntime
  ): Unit = upload(to, objects >>= graphsProducer.apply: _*)

  def runSelect(on: DatasetName, query: SparqlQuery): IO[List[Map[String, String]]] =
    queryRunnerFor(on).flatMap(_.runQuery(query))

  def runUpdate(on: DatasetName, query: SparqlQuery): IO[Unit] =
    queryRunnerFor(on).flatMap(_.runUpdate(query))

  def runUpdates(on: DatasetName, queries: List[SparqlQuery]): IO[Unit] =
    queries.map(runUpdate(on, _)).sequence.void

  def triplesCount(on: DatasetName): IO[Long] =
    queryRunnerFor(on)
      .flatMap(
        _.runQuery(
          SparqlQuery.of("triples count", "SELECT (COUNT(?s) AS ?count) WHERE { GRAPH ?g { ?s ?p ?o } }")
        ).map(_.headOption.map(_.apply("count")).flatMap(_.toLongOption).getOrElse(0L))
      )

  def triplesCount(on: DatasetName, graphId: EntityId): IO[Long] =
    queryRunnerFor(on)
      .flatMap(
        _.runQuery(
          SparqlQuery.of("triples count on graph",
                         s"SELECT (COUNT(?s) AS ?count) WHERE { GRAPH ${graphId.sparql} { ?s ?p ?o } }"
          )
        ).map(_.headOption.map(_.apply("count")).flatMap(_.toLongOption).getOrElse(0L))
      )

  implicit class QueriesOps(queries: List[SparqlQuery]) {
    def runAll(on: DatasetName): IO[Unit] =
      queryRunnerFor(on).flatMap(runner => queries.map(runner.runUpdate).sequence.void)
  }

  private def findConnectionInfo(datasetName: DatasetName): DatasetConnectionConfig = datasets
    .map { case (connectionInfoFactory, _) => connectionInfoFactory(fusekiUrl) }
    .find(_.datasetName == datasetName)
    .getOrElse(throw new Exception(s"Dataset '$datasetName' not registered in Test Jena instance"))

  private implicit lazy val logger: TestLogger[IO] = TestLogger[IO]()

  private lazy val datasetsCreator = TSAdminClient[IO](AdminConnectionConfig(fusekiUrl, adminCredentials))

  protected def queryRunnerFor(datasetName: DatasetName) =
    queryRunner(findConnectionInfo(datasetName))

  private def queryRunner(connectionInfo: DatasetConnectionConfig) =
    TestSparqlQueryTimeRecorder[IO].map { implicit qtr =>
      new TSClientImpl[IO](connectionInfo) {

        import io.circe.Decoder._

        def uploadPayload(jsonLD: JsonLD) = this.upload(jsonLD)

        def runQuery(query: SparqlQuery): IO[List[Map[String, String]]] =
          queryExpecting[List[Map[String, String]]](query)

        def runUpdate(query: SparqlQuery): IO[Unit] = updateWithNoResult(updateQuery = query)

        private implicit lazy val valuesDecoder: Decoder[List[Map[String, String]]] = { cursor =>
          for {
            vars <- cursor.as[List[String]]
            values <- cursor
                        .downField("results")
                        .downField("bindings")
                        .as[List[Map[String, String]]](decodeList(valuesDecoder(vars)))
          } yield values
        }

        private implicit lazy val varsDecoder: Decoder[List[String]] =
          _.downField("head").downField("vars").as[List[Json]].flatMap(_.map(_.as[String]).sequence)

        private def valuesDecoder(vars: List[String]): Decoder[Map[String, String]] =
          implicit cursor =>
            vars
              .map(varToMaybeValue)
              .sequence
              .map(_.flatten)
              .map(_.toMap)

        private def varToMaybeValue(varName: String)(implicit cursor: HCursor) =
          cursor
            .downField(varName)
            .downField("value")
            .as[Option[String]]
            .map(maybeValue => maybeValue map (varName -> _))
      }
    }
}

sealed trait DefaultGraphDataset {
  self: InMemoryJena =>

  def insert(to: DatasetName, triple: Triple)(implicit ioRuntime: IORuntime): Unit =
    queryRunnerFor(to)
      .flatMap(_.runUpdate {
        SparqlQuery.of("insert triple", show"INSERT DATA { ${triple.asSparql} }")
      })
      .unsafeRunSync()

  def delete(from: DatasetName, triple: Triple)(implicit ioRuntime: IORuntime): Unit =
    queryRunnerFor(from)
      .flatMap(_.runUpdate {
        SparqlQuery.of("delete triple", show"DELETE DATA { ${triple.asSparql} }")
      })
      .unsafeRunSync()
}

sealed trait NamedGraphDataset {
  self: InMemoryJena =>

  def delete(from: DatasetName, quad: Quad)(implicit ioRuntime: IORuntime): Unit =
    queryRunnerFor(from)
      .flatMap(_.runUpdate {
        SparqlQuery.of("delete quad", show"DELETE DATA { ${quad.asSparql.sparql} }")
      })
      .unsafeRunSync()

  def insertIO(to: DatasetName, quads: List[Quad]): IO[Unit] =
    quads.map(insertIO(to, _)).sequence.void

  def insertIO(to: DatasetName, quad: Quad): IO[Unit] =
    queryRunnerFor(to) >>= (_.runUpdate {
      SparqlQuery.of("insert quad", show"INSERT DATA { ${quad.asSparql.sparql} }")
    })

  def insert(to: DatasetName, quad: Quad)(implicit ioRuntime: IORuntime): Unit =
    insertIO(to, quad).unsafeRunSync()

  def insert(to: DatasetName, quads: Set[Quad])(implicit ioRuntime: IORuntime): Unit =
    quads.toList.traverse_(insertIO(to, _)).unsafeRunSync()
}

trait GraphsProducer[T] {
  def apply(obj: T)(implicit entityFunctions: EntityFunctions[T]): List[Graph]
}

trait JenaDataset { self: InMemoryJena => }

trait ProjectsDataset extends JenaDataset with NamedGraphDataset {
  self: InMemoryJena =>

  private lazy val configFile: Either[Exception, DatasetConfigFile] = ProjectsTTL.fromTtlFile()
  private lazy val connectionInfoFactory: FusekiUrl => ProjectsConnectionConfig = ProjectsConnectionConfig(
    _,
    BasicAuthCredentials(BasicAuthUsername("renku"), BasicAuthPassword("renku"))
  )

  def projectsDSConnectionInfo: ProjectsConnectionConfig = connectionInfoFactory(fusekiUrl)
  def projectsDataset:          DatasetName              = projectsDSConnectionInfo.datasetName

  registerDataset(connectionInfoFactory, configFile)

  import io.renku.generators.Generators.Implicits._
  import io.renku.graph.model.GraphModelGenerators.projectSlugs
  import io.renku.graph.model.projects.Slug

  private lazy val defaultProjectForGraph: Slug = projectSlugs.generateOne

  def defaultProjectGraphId(implicit renkuUrl: RenkuUrl): EntityId =
    io.renku.graph.model.testentities.Project.toEntityId(defaultProjectForGraph)

  protected implicit def projectsDSGraphsProducer[A](implicit
      renkuUrl: RenkuUrl,
      glApiUrl: GitLabApiUrl
  ): GraphsProducer[A] = new GraphsProducer[A] {
    import io.renku.graph.model.entities
    import io.renku.jsonld.NamedGraph
    import io.renku.jsonld.syntax._

    override def apply(entity: A)(implicit entityFunctions: EntityFunctions[A]): List[Graph] =
      List(maybeBuildProjectGraph(entity), maybeBuildPersonsGraph(entity)).flatten

    private def maybeBuildProjectGraph(entity: A)(implicit entityFunctions: EntityFunctions[A]) = {
      implicit val projectEnc: JsonLDEncoder[A] = entityFunctions.encoder(GraphClass.Project)
      entity.asJsonLD match {
        case jsonLD: JsonLDEntityLike => NamedGraph(projectGraphId(entity), jsonLD).some
        case jsonLD: JsonLDArray      => NamedGraph.fromJsonLDsUnsafe(projectGraphId(entity), jsonLD).some
        case _ => None
      }
    }

    private def maybeBuildPersonsGraph(entity: A)(implicit entityFunctions: EntityFunctions[A]) = {
      implicit val graph: GraphClass = GraphClass.Persons
      entityFunctions.findAllPersons(entity).toList.map(_.asJsonLD) match {
        case Nil    => None
        case h :: t => NamedGraph.fromJsonLDsUnsafe(GraphClass.Persons.id, h, t: _*).some
      }
    }

    private def projectGraphId(entity: A): EntityId = entity match {
      case p: entities.Project     => GraphClass.Project.id(p.resourceId)
      case p: testentities.Project => GraphClass.Project.id(projects.ResourceId(p.asEntityId))
      case _ => defaultProjectGraphId
    }
  }
}

trait MigrationsDataset extends JenaDataset with DefaultGraphDataset {
  self: InMemoryJena =>

  private lazy val configFile: Either[Exception, MigrationsTTL] = MigrationsTTL.fromTtlFile()
  private lazy val connectionInfoFactory: FusekiUrl => MigrationsConnectionConfig = MigrationsConnectionConfig(
    _,
    BasicAuthCredentials(BasicAuthUsername("admin"), BasicAuthPassword("admin"))
  )

  def migrationsDSConnectionInfo: MigrationsConnectionConfig = connectionInfoFactory(fusekiUrl)
  def migrationsDataset:          DatasetName                = migrationsDSConnectionInfo.datasetName

  registerDataset(connectionInfoFactory, configFile)

  implicit def entityFunctions[A](implicit entityEncoder: JsonLDEncoder[A]): EntityFunctions[A] =
    new EntityFunctions[A] {
      override val findAllPersons: A => Set[Person]               = _ => Set.empty
      override val encoder:        GraphClass => JsonLDEncoder[A] = _ => entityEncoder
    }

  protected implicit def graphsProducer[A]: GraphsProducer[A] = new GraphsProducer[A] {

    import io.renku.jsonld.DefaultGraph
    import io.renku.jsonld.syntax._

    override def apply(entity: A)(implicit entityFunctions: EntityFunctions[A]): List[Graph] = {
      implicit val enc: JsonLDEncoder[A] = entityFunctions.encoder(GraphClass.Default)
      List(DefaultGraph.fromJsonLDsUnsafe(entity.asJsonLD))
    }
  }
}
