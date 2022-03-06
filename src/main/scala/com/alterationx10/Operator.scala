package com.alterationx10

import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.v1.secrets.Secrets
import zio._
import zio.clock.Clock
import zio.logging.Logging
import zio.magic._

object Operator extends App {

  val simpleProgram: ZIO[Has[SQLService], Nothing, Unit] =
    SQLService(_.createDatabaseWithRole("altx11"))
      .as(())
      .catchAll(_ => ZIO.unit)

  val operatorProgram: ZIO[Has[
    DatabaseOperator
  ] with Clock with Logging, Nothing, ExitCode] = {
    for {
      op <- DatabaseOperator(_.operator)
      _  <- op.start()
      _  <- ZIO.never
    } yield ExitCode.success
  }.catchAll(_ => ZIO.succeed(ExitCode.failure))

  val operatorLayer = ZLayer.wire[Has[
    DatabaseOperator
  ] with Clock with Logging](
    ZEnv.live,
    Logging.ignore,
    SQLService.live,
    k8sDefault,
    Secrets.live,
    DatabaseOperator.live
  )
  override def run(args: List[String]): zio.URIO[zio.ZEnv, zio.ExitCode] =
    operatorProgram.provideLayer(operatorLayer.orDie)

}
