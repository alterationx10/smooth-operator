package com.alterationx10

import com.coralogix.zio.k8s.client.com.alterationx10.definitions.database.v1.Database
import com.coralogix.zio.k8s.client.com.alterationx10.v1.databases.Databases
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.v1.secrets.Secrets
import com.coralogix.zio.k8s.client.{K8sFailure, NamespacedResource}
import com.coralogix.zio.k8s.model.core.v1.Secret
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.coralogix.zio.k8s.operator.Operator.EventProcessor
import com.coralogix.zio.k8s.operator._
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio._
import zio.clock.Clock
import zio.console.Console

import scala.language.postfixOps

trait DatabaseOperator {
  def operator: Task[Operator[
    Clock,
    Throwable,
    Database
  ]]
}

case class DatabaseOperatorLive(
    cnsl: Console.Service,
    sqlService: SQLService,
    secrets: Secrets.Service,
    sttp: SttpBackend[Task, ZioStreams with WebSockets],
    k8s: K8sCluster
) extends DatabaseOperator {

  def upsertSecret(
      secret: Secret
  ): ZIO[Clock, K8sFailure, Secret] = {
    for {
      nm       <- secret.getName
      ns       <- secret.getMetadata.flatMap(_.getNamespace)
      existing <- secrets.get(nm, K8sNamespace(ns)).option
      sec <- existing match {
        case Some(_) => secrets.replace(nm, secret, K8sNamespace(ns))
        case None    => secrets.create(secret, K8sNamespace(ns))
      }
    } yield sec
  }

  def processItem(item: Database): URIO[Clock, Unit] =
    (for {
      // Get all of our databases
      dbs <- ZIO.fromOption(item.spec.flatMap(_.databases).toOption)
      // For each database
      _ <- ZIO.foreach(dbs) { db =>
        (for {
          _ <- cnsl.putStrLn(s"Processing $db...")
          // Create things
          pw <- sqlService.createDatabaseWithRole(db)
          _  <- cnsl.putStrLn(s"... $db created ...")
          // Put the generated PW in a k8s secret
          _ <- upsertSecret(
            Secret(
              metadata = Some(
                ObjectMeta(
                  name = Option(db),
                  namespace = item.metadata
                    .map(_.namespace)
                    .getOrElse(Option("default"))
                )
              ),
              data = Map(
                "POSTGRES_PASSWORD" -> Chunk.fromArray(
                  pw.getBytes()
                )
              )
            )
          ).tapError(e => cnsl.putStrLnErr(s"Couldn't make secret:\n $e"))
          _ <- cnsl.putStrLn(s"... Secret created for $db")
        } yield ()).ignore
      }
    } yield ()).ignore

  val eventProcessor: EventProcessor[Clock, Throwable, Database] =
    (ctx, event) =>
      event match {
        case Reseted() =>
          cnsl.putStrLn(s"Reseted - will (re) add any existing").ignore
        case Added(item) =>
          processItem(item)
        case Modified(item) =>
          processItem(item)
        case Deleted(item) =>
          cnsl.putStrLn(s"Deleted - but not performing action").ignore
      }

  val dbClientLayer: ZLayer[Any, Throwable, Has[NamespacedResource[Database]]] =
    (ZLayer.succeed(k8s) ++ ZLayer.succeed(sttp)) >>> Databases.live.map(
      _.get.asGeneric
    )

  override def operator: Task[Operator[Clock, Throwable, Database]] =
    (
      Operator
        .namespaced(
          eventProcessor
        )(namespace = None, buffer = 1024)
      )
      .provideLayer(dbClientLayer)

}

object DatabaseOperator extends Accessible[DatabaseOperator] {
  val live: URLayer[Has[Console.Service] with Has[SQLService] with Has[
    Secrets.Service
  ] with Has[SttpBackend[Task, ZioStreams with WebSockets]] with Has[
    K8sCluster
  ], Has[DatabaseOperator]] = (DatabaseOperatorLive.apply _).toLayer
}
