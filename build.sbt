
enablePlugins(JvmPlugin)

lazy val sharedSettings = Seq(
  name := "flashbot",
  organization := "io.flashbook",
  version := "0.1-SNAPSHOT",
  scalaVersion := "2.11.8",
  resolvers += Resolver.sonatypeRepo("snapshots")
)

lazy val akkaVersion = "2.5.11"
lazy val akkaHttpVersion = "10.1.0"
lazy val circeVersion = "0.9.0"

lazy val networkDeps = List(
  // Akka libs
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,

  // CORS
  "ch.megard" %% "akka-http-cors" % "0.3.0",

  // Persistent storage
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",

//  "com.github.andyglow" %% "websocket-scala-client" % "0.2.4" % Compile,
  "org.java-websocket" % "Java-WebSocket" % "1.3.8",

  "de.heikoseeberger" %% "akka-http-circe" % "1.20.0",

  // Pusher
  "com.pusher" % "pusher-java-client" % "1.8.1"
)

lazy val testDeps = List(
  "org.scalactic" %% "scalactic" % "3.0.5",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
)

lazy val graphQLDeps = List(
  "org.sangria-graphql" %% "sangria" % "1.4.0",
  "org.sangria-graphql" %% "sangria-circe" % "1.2.1",
  "org.sangria-graphql" %% "sangria-akka-streams" % "1.0.0"
)

lazy val jsonDeps = List(
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "io.circe" %% "circe-optics" % circeVersion,
  "io.circe" %% "circe-literal" % circeVersion
)

lazy val dataStores = List(
  "net.openhft" % "chronicle-queue" % "4.6.99",
  "net.openhft" % "chronicle-map" % "3.14.5"
)

lazy val serviceDeps = List(
  "com.github.scopt" % "scopt_2.11" % "3.7.0",
  "com.typesafe" % "config" % "1.3.2" % Compile,
  // Metrics with prometheus
  "io.prometheus" % "simpleclient" % "0.3.0",
  "io.prometheus" % "simpleclient_httpserver" % "0.3.0"
)

lazy val timeSeriesDeps = List(
  "org.ta4j" % "ta4j-core" % "0.12-SNAPSHOT"
)

lazy val miscDeps = List(
  // Reflection utility for discovering classes that implement an interface.
  "org.clapper" %% "classutil" % "1.1.2",
  "ai.x" %% "diff" % "1.2.0"
)

lazy val root = project.in(file("."))
  .settings(sharedSettings: _*)
  .settings(assemblyJarName in assembly := "flashbot.jar")
  .settings(assemblyMergeStrategy in assembly := (x => {
    val defaultStrategy = (assemblyMergeStrategy in assembly).value
    val orig = defaultStrategy(x)
    orig match {
      case MergeStrategy.deduplicate => MergeStrategy.first // scary
      case other => other
    }
  }))
  .settings(libraryDependencies ++= (serviceDeps ++ networkDeps ++ jsonDeps ++ graphQLDeps ++
    dataStores ++ timeSeriesDeps ++ testDeps ++ miscDeps))


// Hack to stop SBT from complaining
// https://github.com/sbt/sbt/issues/3618
// Relevant to the jax rs ws dependency of xchange libraries
// All that mess is commented out for now anyway
//
//val workaround = {
//  sys.props += ("packaging.type" -> "jar")
//  ()
//}
//lazy val marketDataDeps = List(
//  "info.bitrich.xchange-stream" % "xchange-stream-core" % "4.3.2" % Compile,
//  "info.bitrich.xchange-stream" % "xchange-gdax" % "4.3.2" % Compile
//)








//lazy val server = project
//  .in(file("server"))
//  .settings(sharedSettings: _*)
//  .settings(name := "flashbot-server")
//  .settings(libraryDependencies ++= (serviceDeps ++ akkaDeps ++ jsonDeps ++ graphQLDeps ++
//    dataStores ++ testDeps))
//  .dependsOn(common)
//  .aggregate(common)
//

//lazy val ui = project
//  .in(file("ui"))
//  .settings(sharedSettings: _*)
//  .settings(name := "doomsday-ui")
//  .settings(mainClass in Compile := Some("ui.Main"))
//  .settings(scalaJSUseMainModuleInitializer in Compile := true)
//  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)
//    .settings(npmDependencies in Compile ++= Seq(
//      "react" -> "16.2.0",
//      "react-dom" -> "16.2.0"
//    ))
//  .dependsOn(common)
//  .aggregate(common)

//lazy val common = project
//  .in(file("common"))
//  .settings(sharedSettings: _*)
//  .settings(name := "flashbot-common")
//  .settings(libraryDependencies ++= (serviceDeps ++ akkaDeps ++ jsonDeps ++ graphQLDeps ++
//    dataStores ++ testDeps))

//lazy val root = project.in(file("."))
//    .aggregate(doomsdayJS, doomsdayJVM)
//    .settings(
//      publish := {},
//      publishLocal := {}
//    )

//lazy val doomsday = crossProject.in(file("."))
//  .settings(
//    name := "doomsday",
//    version := "0.1-SNAPSHOT"
////    scalaJSUseMainModuleInitializer := true
//  )
//  .jvmSettings(
//    libraryDependencies ++= (akkaDeps ++ Seq(
//      "org.scalactic" %% "scalactic" % "3.0.5",
//      "org.scalatest" %% "scalatest" % "3.0.5" % "test",
//      "org.sangria-graphql" %% "sangria" % "1.4.0"
//    ))
//  )
//    .jvmSettings(moduleName := "jvm")
//  .jsSettings(
//    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "0.9.1"
//  )
//  .jsSettings(moduleName := "js")

//lazy val doomsdayJS = doomsday.js
//lazy val doomsdayJVM = doomsday.jvm
