import sbt.*

//noinspection TypeAnnotation
object Dependencies {

  object V {
    val ammonite               = "2.4.1"
    val catsCore               = "2.10.0"
    val catsEffect             = "3.5.2"
    val catsEffectScalaTest    = "1.5.0"
    val catsEffectMunit        = "1.0.7"
    val scalacheckEffectMunit  = "1.0.4"
    val circeCore              = "0.14.6"
    val circeGenericExtras     = "0.14.3"
    val circeOptics            = "0.15.0"
    val diffx                  = "0.9.0"
    val fs2                    = "3.9.3"
    val http4s                 = "0.23.24"
    val http4sEmber            = "0.23.24"
    val http4sPrometheus       = "0.24.6"
    val ip4s                   = "3.4.0"
    val jsonld4s               = "0.13.0"
    val log4cats               = "2.6.0"
    val log4jCore              = "2.21.1"
    val logback                = "1.4.11"
    val luceneQueryParser      = "9.8.0"
    val owlapi                 = "5.5.0"
    val pureconfig             = "0.17.4"
    val rdf4jQueryParserSparql = "4.3.8"
    val refined                = "0.11.0"
    val refinedPureconfig      = "0.11.0"
    val scalacheck             = "1.17.0"
    val scalamock              = "5.2.0"
    val scalatest              = "3.2.17"
    val scalatestScalacheck    = "3.2.14.0"
    val sentryLogback          = "6.33.1"
    val skunk                  = "0.6.1"
    val swaggerParser          = "2.1.18"
    val testContainersScala    = "0.41.0"
    val widoco                 = "1.4.20"
    val wiremock               = "3.3.1"
  }

  val ip4s = Seq(
    "com.comcast" %% "ip4s-core" % V.ip4s
  )

  val scalatestScalaCheck = Seq(
    "org.scalatestplus" %% "scalacheck-1-16" % V.scalatestScalacheck
  )

  val scalatest = Seq(
    "org.scalatest" %% "scalatest" % V.scalatest
  )

  val ammoniteOps = Seq(
    "com.lihaoyi" %% "ammonite-ops" % V.ammonite
  )

  val rdf4jQueryParserSparql = Seq(
    "org.eclipse.rdf4j" % "rdf4j-queryparser-sparql" % V.rdf4jQueryParserSparql
  )

  val diffx = Seq(
    "com.softwaremill.diffx" %% "diffx-scalatest-should" % V.diffx
  )

  val swaggerParser = Seq(
    "io.swagger.parser.v3" % "swagger-parser" % V.swaggerParser
  )

  val widoco = Seq(
    "com.github.dgarijo" % "widoco" % V.widoco,
    // needed by widoco only - explicitly bumped up to work with rdf4j-queryparser-sparql (triples-store-client)
    // from 5.1.18 that widoco comes with
    "net.sourceforge.owlapi" % "owlapi-distribution" % V.owlapi,
    // needed by widoco only
    "org.apache.logging.log4j" % "log4j-core" % V.log4jCore
  )

  val pureconfig = Seq(
    "com.github.pureconfig" %% "pureconfig"      % V.pureconfig,
    "com.github.pureconfig" %% "pureconfig-cats" % V.pureconfig
  )

  val refinedPureconfig = Seq(
    "eu.timepit" %% "refined-pureconfig" % V.refinedPureconfig
  )

  val sentryLogback = Seq(
    "io.sentry" % "sentry-logback" % V.sentryLogback
  )

  val luceneQueryParser = Seq(
    "org.apache.lucene" % "lucene-queryparser" % V.luceneQueryParser
  )

  val luceneAnalyzer = Seq(
    "org.apache.lucene" % "lucene-analysis-common" % V.luceneQueryParser
  )

  val http4sClient = Seq(
    "org.http4s" %% "http4s-ember-client" % V.http4sEmber
  )
  val http4sServer = Seq(
    "org.http4s" %% "http4s-ember-server" % V.http4sEmber,
    "org.http4s" %% "http4s-server"       % V.http4s
  )
  val http4sCirce = Seq(
    "org.http4s" %% "http4s-circe" % V.http4s
  )
  val http4sDsl = Seq(
    "org.http4s" %% "http4s-dsl" % V.http4s
  )
  val http4sPrometheus = Seq(
    "org.http4s" %% "http4s-prometheus-metrics" % V.http4sPrometheus
  )

  val skunk = Seq(
    "org.tpolecat" %% "skunk-core" % V.skunk
  )

  val catsEffect = Seq(
    "org.typelevel" %% "cats-effect" % V.catsEffect
  )

  val catsEffectScalaTest = Seq(
    "org.typelevel" %% "cats-effect-testing-scalatest" % V.catsEffectScalaTest
  )

  val catsEffectMunit = Seq(
    "org.typelevel" %% "munit-cats-effect-3" % V.catsEffectMunit
  )

  val scalacheckEffectMunit = Seq(
    "org.typelevel" %% "scalacheck-effect-munit" % V.scalacheckEffectMunit
  )

  val log4Cats = Seq(
    "org.typelevel" %% "log4cats-core" % V.log4cats
  )

  val testContainersScalaTest = Seq(
    "com.dimafeng" %% "testcontainers-scala-scalatest" % V.testContainersScala
  )
  val testContainersPostgres =
    testContainersScalaTest ++
      Seq(
        "com.dimafeng" %% "testcontainers-scala-postgresql" % V.testContainersScala
      )

  val wiremock = Seq(
    "org.wiremock" % "wiremock" % V.wiremock
  )

  val scalamock = Seq(
    "org.scalamock" %% "scalamock" % V.scalamock
  )

  val logbackClassic = Seq(
    "ch.qos.logback" % "logback-classic" % V.logback
  )

  val refined = Seq(
    "eu.timepit" %% "refined" % V.refined
  )

  val circeCore = Seq(
    "io.circe" %% "circe-core" % V.circeCore
  )
  val circeLiteral = Seq(
    "io.circe" %% "circe-literal" % V.circeCore
  )
  val circeGeneric = Seq(
    "io.circe" %% "circe-generic" % V.circeCore
  )
  val circeGenericExtras = Seq(
    "io.circe" %% "circe-generic-extras" % V.circeGenericExtras
  )
  val circeParser = Seq(
    "io.circe" %% "circe-parser" % V.circeCore
  )

  val circeOptics = Seq(
    "io.circe" %% "circe-optics" % V.circeOptics
  )

  val jsonld4s = Seq(
    "io.renku" %% "jsonld4s" % V.jsonld4s
  )

  val fs2Core = Seq(
    "co.fs2" %% "fs2-core" % V.fs2
  )

  val catsCore = Seq(
    "org.typelevel" %% "cats-core" % V.catsCore
  )

  val catsFree = Seq(
    "org.typelevel" %% "cats-free" % V.catsCore
  )

  val scalacheck = Seq(
    "org.scalacheck" %% "scalacheck" % V.scalacheck
  )
}
