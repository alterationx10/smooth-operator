package com.alterationx10

import zio._
import zio.console.Console

import java.sql.{Connection, DriverManager, Statement}

trait SQLService {
  def createDatabaseWithRole(db: String): Task[String]
}

object SQLService extends Accessible[SQLService] {

  val live: URLayer[Has[Console.Service], Has[
    SQLService
  ]] = (SQLServiceLive.apply _).toLayer

  val provided: ZLayer[Any, Nothing, Has[SQLService]] =
    ZEnv.live >>> live

}

case class SQLServiceLive(cnsl: Console.Service) extends SQLService {

  private val acquireConnection =
    ZIO.effect {
      val url = {
        sys.env.getOrElse(
          "PG_CONN_URL",
          "jdbc:postgresql://localhost:5432/?user=postgres&password=password"
        )
      }
      DriverManager.getConnection(url)
    }

  private val managedConnection: ZManaged[Any, Throwable, Connection] =
    ZManaged.fromAutoCloseable(acquireConnection)

  private def acquireStatement(conn: Connection): Task[Statement] =
    Task.effect {
      conn.createStatement
    }

  def managedStatement(conn: Connection): ZManaged[Any, Throwable, Statement] =
    ZManaged.fromAutoCloseable(acquireStatement(conn))

  def executeSql: Statement => String => ZIO[Any, Throwable, Unit] =
    st =>
      sql =>
        ZIO
          .effect(st.execute(sql))
          .unit
          .tapBoth(
            err => cnsl.putStrLnErr(err.getMessage),
            _ => cnsl.putStrLn(sql)
          )

  override def createDatabaseWithRole(db: String): Task[String] = {
    managedConnection.use { conn =>
      managedStatement(conn).use { st =>
        for {
          pw <- ZIO.effect(scala.util.Random.alphanumeric.take(6).mkString)
          _  <- executeSql(st)(s"CREATE DATABASE $db")
          _  <- executeSql(st)(s"CREATE USER $db PASSWORD '$pw'")
          _  <- executeSql(st)(s"GRANT ALL ON DATABASE $db TO $db")
        } yield pw
      }
    }
  }
}
