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

package io.renku.eventlog

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.scalatest.Suite
import skunk._
import skunk.codec.all._
import skunk.implicits._

/** Utilities for event log  database. You can swap [[ContainerEventLogDb]] with [[ExternalEventLogDb]] for more
 *  convenient debugging. 
 */
trait InMemoryEventLogDb extends ContainerEventLogDb with TypeSerializers {
  self: Suite =>

  implicit val ioRuntime: IORuntime

  def executeIO[O](query: Kleisli[IO, Session[IO], O]): IO[O] =
    sessionResource.useK(query)

  def execute[O](query: Kleisli[IO, Session[IO], O]): O =
    executeIO(query).unsafeRunSync()

  def executeCommand(sql: Command[Void]): Unit =
    execute[Unit](Kleisli(session => session.execute(sql).void))

  def verifyTrue(sql: Command[Void]): Unit = execute[Unit](Kleisli(session => session.execute(sql).void))

  def verify(table: String, column: String, hasType: String): Boolean = execute[Boolean] {
    Kleisli { session =>
      val query: Query[String *: String *: EmptyTuple, String] =
        sql"""SELECT data_type FROM information_schema.columns WHERE
         table_name = $varchar AND column_name = $varchar;""".query(varchar)
      session
        .prepare(query)
        .flatMap(_.unique(table *: column *: EmptyTuple))
        .map(dataType => dataType == hasType)
        .recover { case _ => false }
    }
  }

  def verifyColumnExists(table: String, column: String): Boolean = execute[Boolean] {
    Kleisli { session =>
      val query: Query[String *: String *: EmptyTuple, Boolean] =
        sql"""SELECT EXISTS (
                SELECT *
                FROM information_schema.columns 
                WHERE table_name = $varchar AND column_name = $varchar
              )""".query(bool)
      session
        .prepare(query)
        .flatMap(_.unique(table *: column *: EmptyTuple))
        .recover { case _ => false }
    }
  }

  def verifyConstraintExists(table: String, constraintName: String): Boolean = execute[Boolean] {
    Kleisli { session =>
      val query: Query[String *: String *: EmptyTuple, Boolean] =
        sql"""SELECT EXISTS (
                 SELECT * 
                 FROM information_schema.constraint_column_usage 
                 WHERE table_name = $varchar AND constraint_name = $varchar                            
               )""".query(bool)
      session
        .prepare(query)
        .flatMap(_.unique(table *: constraintName *: EmptyTuple))
        .recover { case _ => false }
    }
  }

  def verifyIndexExists(table: String, indexName: String): Boolean = execute[Boolean] {
    Kleisli { session =>
      val query: Query[String *: String *: EmptyTuple, Boolean] =
        sql"""SELECT EXISTS (
                 SELECT * 
                 FROM pg_indexes 
                 WHERE tablename = $varchar AND indexname = $varchar                            
               )""".query(bool)
      session
        .prepare(query)
        .flatMap(_.unique(table *: indexName *: EmptyTuple))
        .recover { case _ => false }
    }
  }

  def tableExists(tableName: String): Boolean = execute[Boolean] {
    Kleisli { session =>
      val query: Query[String, Boolean] =
        sql"SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = $varchar)".query(bool)
      session.prepare(query).flatMap(_.unique(tableName)).recover { case _ => false }
    }
  }

  def viewExists(viewName: String): Boolean = execute[Boolean] {
    Kleisli { session =>
      val query: Query[Void, Boolean] = sql"select exists (select * from #$viewName)".query(bool)
      session.unique(query).recover { case _ => false }
    }
  }

  def dropTable(tableName: String): Unit = execute[Unit] {
    Kleisli { session =>
      val query: Command[Void] = sql"DROP TABLE IF EXISTS #$tableName CASCADE".command
      session.execute(query).void
    }
  }
}
