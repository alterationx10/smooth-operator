lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := "2.13.8",
    organization := "com.alterationx10",
    name         := "smooth-operator",
    version      := "0.0.1",
    libraryDependencies ++= Seq(
      "com.coralogix"                 %% "zio-k8s-operator"       % "1.4.3",
      "com.coralogix"                 %% "zio-k8s-client"         % "1.4.3",
      "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % "3.3.18", //
      "com.softwaremill.sttp.client3" %% "slf4j-backend"          % "3.3.18",
      "org.postgresql"                 % "postgresql"             % "42.3.1",
      "io.github.kitlangton"          %% "zio-magic"              % "0.3.11"
    ),
    dockerBaseImage := "openjdk:17.0.2-slim-buster"
  )
  .enablePlugins(JavaServerAppPackaging)

externalCustomResourceDefinitions := Seq(
  file("crds/databases.yaml")
)

enablePlugins(K8sCustomResourceCodegenPlugin)
