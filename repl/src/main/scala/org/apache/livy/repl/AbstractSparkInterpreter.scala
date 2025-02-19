/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.repl

import java.io.ByteArrayOutputStream

import scala.tools.nsc.interpreter.Results

import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.json4s.DefaultFormats
import org.json4s.Extraction
import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import org.apache.livy.Logging
import org.apache.livy.rsc.driver.SparkEntries

object AbstractSparkInterpreter {
  private[repl] val KEEP_NEWLINE_REGEX = """(?<=\n)""".r
  private val MAGIC_REGEX = "^%(\\w+)\\W*(.*)".r
}

abstract class AbstractSparkInterpreter extends Interpreter with Logging {
  import AbstractSparkInterpreter._

  private implicit def formats = DefaultFormats

  protected val outputStream = new ByteArrayOutputStream()

  protected var entries: SparkEntries = _

  def sparkEntries(): SparkEntries = entries

  final def kind: String = "spark"

  protected def isStarted(): Boolean

  protected def interpret(code: String): Results.Result

  protected def completeCandidates(code: String, cursor: Int) : Array[String] = Array()

  protected def valueOfTerm(name: String): Option[Any]

  protected def bind(name: String, tpe: String, value: Object, modifier: List[String]): Unit

  protected def conf: SparkConf

  protected def postStart(): Unit = {
    entries = new SparkEntries(conf)

    if (isSparkSessionPresent()) {
      bind("spark",
        sparkEntries.sparkSession().getClass.getCanonicalName,
        sparkEntries.sparkSession(),
        List("""@transient"""))
      bind("sc", "org.apache.spark.SparkContext", sparkEntries.sc().sc, List("""@transient"""))

      execute("import org.apache.spark.SparkContext._")
      execute("import spark.implicits._")
      execute("import spark.sql")
      execute("import org.apache.spark.sql.functions._")
      val postStart = execute("import zippo._")
      info(s" AfterPostStart -- spark session present is present ${postStart}")
    } else {
      bind("sc", "org.apache.spark.SparkContext", sparkEntries.sc().sc, List("""@transient"""))
      val sqlContext = Option(sparkEntries.hivectx()).getOrElse(sparkEntries.sqlctx())
      bind("sqlContext", sqlContext.getClass.getCanonicalName, sqlContext, List("""@transient"""))

      execute("import org.apache.spark.SparkContext._")
      execute("import sqlContext.implicits._")
      execute("import sqlContext.sql")
      execute("import org.apache.spark.sql.functions._")
      val postStart = execute("import zippo._")
      info(s" AfterPostStart -- no spark session present ${postStart}")
    }
  }

  override def close(): Unit = {
    if (entries != null) {
      entries.stop()
      entries = null
    }
  }

  private def isSparkSessionPresent(): Boolean = {
    try {
      Class.forName("org.apache.spark.sql.SparkSession")
      true
    } catch {
      case _: ClassNotFoundException | _: NoClassDefFoundError => false
    }
  }

  override protected[repl] def execute(code: String): Interpreter.ExecuteResponse =
    restoreContextClassLoader {
      require(isStarted())

      executeLines(code.trim.split("\n").toList, Interpreter.ExecuteSuccess(JObject(
        (TEXT_PLAIN, JString(""))
      )))
  }

  override protected[repl] def complete(code: String, cursor: Int): Array[String] = {
      completeCandidates(code, cursor)
  }

  private def executeMagic(magic: String, rest: String): Interpreter.ExecuteResponse = {
    magic match {
      case "json" => executeJsonMagic(rest)
      case "table" => executeTableMagic(rest)
      case _ =>
        Interpreter.ExecuteError("UnknownMagic", f"Unknown magic command $magic")
    }
  }

  private def executeJsonMagic(name: String): Interpreter.ExecuteResponse = {
    try {
      val value = valueOfTerm(name) match {
        case Some(obj: RDD[_]) => obj.asInstanceOf[RDD[_]].take(10)
        case Some(obj) => obj
        case None => return Interpreter.ExecuteError("NameError", f"Value $name does not exist")
      }

      Interpreter.ExecuteSuccess(JObject(
        (APPLICATION_JSON, Extraction.decompose(value))
      ))
    } catch {
      case _: Throwable =>
        Interpreter.ExecuteError("ValueError", "Failed to convert value into a JSON value")
    }
  }

  private class TypesDoNotMatch extends Exception

  private def convertTableType(value: JValue): String = {
    value match {
      case (JNothing | JNull) => "NULL_TYPE"
      case JBool(_) => "BOOLEAN_TYPE"
      case JString(_) => "STRING_TYPE"
      case JInt(_) => "BIGINT_TYPE"
      case JDouble(_) => "DOUBLE_TYPE"
      case JDecimal(_) => "DECIMAL_TYPE"
      case JArray(arr) =>
        if (allSameType(arr.iterator)) {
          "ARRAY_TYPE"
        } else {
          throw new TypesDoNotMatch
        }
      case JObject(obj) =>
        if (allSameType(obj.iterator.map(_._2))) {
          "MAP_TYPE"
        } else {
          throw new TypesDoNotMatch
        }
    }
  }

  private def allSameType(values: Iterator[JValue]): Boolean = {
    if (values.hasNext) {
      val type_name = convertTableType(values.next())
      values.forall { case value => type_name.equals(convertTableType(value)) }
    } else {
      true
    }
  }

  private def executeTableMagic(name: String): Interpreter.ExecuteResponse = {
    val value = valueOfTerm(name) match {
      case Some(obj: RDD[_]) => obj.asInstanceOf[RDD[_]].take(10)
      case Some(obj) => obj
      case None => return Interpreter.ExecuteError("NameError", f"Value $name does not exist")
    }

    extractTableFromJValue(Extraction.decompose(value))
  }

  private def extractTableFromJValue(value: JValue): Interpreter.ExecuteResponse = {
    // Convert the value into JSON and map it to a table.
    val rows: List[JValue] = value match {
      case JArray(arr) => arr
      case _ => List(value)
    }

    try {
      val headers = scala.collection.mutable.Map[String, Map[String, String]]()

      val data = rows.map { case row =>
        val cols: List[JField] = row match {
          case JArray(arr: List[JValue]) =>
            arr.zipWithIndex.map { case (v, index) => JField(index.toString, v) }
          case JObject(obj) => obj.sortBy(_._1)
          case value: JValue => List(JField("0", value))
        }

        cols.map { case (k, v) =>
          val typeName = convertTableType(v)

          headers.get(k) match {
            case Some(header) =>
              if (header.get("type").get != typeName) {
                throw new TypesDoNotMatch
              }
            case None =>
              headers.put(k, Map(
                "type" -> typeName,
                "name" -> k
              ))
          }

          v
        }
      }

      Interpreter.ExecuteSuccess(
        APPLICATION_LIVY_TABLE_JSON -> (
          ("headers" -> headers.toSeq.sortBy(_._1).map(_._2)) ~ ("data" -> data)
        ))
    } catch {
      case _: TypesDoNotMatch =>
        Interpreter.ExecuteError("TypeError", "table rows have different types")
    }
  }

  private def executeLines(
      lines: List[String],
      resultFromLastLine: Interpreter.ExecuteResponse): Interpreter.ExecuteResponse = {
    lines match {
      case Nil => resultFromLastLine
      case head :: tail =>
        val result = executeLine(head)

        result match {
          case Interpreter.ExecuteIncomplete() =>
            tail match {
              case Nil =>
                // ExecuteIncomplete could be caused by an actual incomplete statements (e.g. "sc.")
                // or statements with just comments.
                // To distinguish them, reissue the same statement wrapped in { }.
                // If it is an actual incomplete statement, the interpreter will return an error.
                // If it is some comment, the interpreter will return success.
                executeLine(s"{\n$head\n}") match {
                  case Interpreter.ExecuteIncomplete() | Interpreter.ExecuteError(_, _, _) =>
                    // Return the original error so users won't get confusing error message.
                    result
                  case _ => resultFromLastLine
                }
              case next :: nextTail =>
                executeLines(head + "\n" + next :: nextTail, resultFromLastLine)
            }
          case Interpreter.ExecuteError(_, _, _) =>
            result

          case Interpreter.ExecuteAborted(_) =>
            result

          case Interpreter.ExecuteSuccess(e) =>
            val mergedRet = resultFromLastLine match {
              case Interpreter.ExecuteSuccess(s) =>
                // Because of SparkMagic related specific logic, so we will only merge text/plain
                // result. For any magic related output, still follow the old way.
                if (s.values.contains(TEXT_PLAIN) && e.values.contains(TEXT_PLAIN)) {
                  val lastRet = s.values.getOrElse(TEXT_PLAIN, "").asInstanceOf[String]
                  val currRet = e.values.getOrElse(TEXT_PLAIN, "").asInstanceOf[String]
                  if (lastRet.nonEmpty && currRet.nonEmpty) {
                    Interpreter.ExecuteSuccess(TEXT_PLAIN -> s"$lastRet$currRet")
                  } else if (lastRet.nonEmpty) {
                    Interpreter.ExecuteSuccess(TEXT_PLAIN -> lastRet)
                  } else if (currRet.nonEmpty) {
                    Interpreter.ExecuteSuccess(TEXT_PLAIN -> currRet)
                  } else {
                    result
                  }
                } else {
                  result
                }

              case _ => result
            }

            executeLines(tail, mergedRet)
        }
    }
  }

  private def executeLine(code: String): Interpreter.ExecuteResponse = {
    code match {
      case MAGIC_REGEX(magic, rest) =>
        executeMagic(magic, rest)
      case _ =>
        scala.Console.withOut(outputStream) {
          interpret(code) match {
            case Results.Success =>
              Interpreter.ExecuteSuccess(
                TEXT_PLAIN -> readStdout()
              )
            case Results.Incomplete => Interpreter.ExecuteIncomplete()
            case Results.Error =>
              val (ename, traceback) = parseError(readStdout())
              Interpreter.ExecuteError("Error", ename, traceback)
          }
        }
    }
  }

  protected[repl] def parseError(stdout: String): (String, Seq[String]) = {
    // An example of Scala compile error message:
    // <console>:27: error: type mismatch;
    //  found   : Int
    //  required: Boolean

    // An example of Scala runtime exception error message:
    // java.lang.RuntimeException: message
    //   at .error(<console>:11)
    //   ... 32 elided

    // Return the first line as ename. Lines following as traceback.

    val lines = KEEP_NEWLINE_REGEX.split(stdout)
    val ename = lines.headOption.map(_.trim).getOrElse("unknown error")
    val traceback = lines.tail

    (ename, traceback)
  }

  protected def restoreContextClassLoader[T](fn: => T): T = {
    val currentClassLoader = Thread.currentThread().getContextClassLoader()
    try {
      fn
    } finally {
      Thread.currentThread().setContextClassLoader(currentClassLoader)
    }
  }

  private def readStdout() = {
    val output = outputStream.toString("UTF-8")
    outputStream.reset()

    output
  }
}
