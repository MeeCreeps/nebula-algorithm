/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License,
 * attached with Common Clause Condition 1.0, found in the LICENSES directory.
 */

package com.vesoft.nebula.connector.writer

import com.vesoft.nebula.connector.connector.{NebulaVertex, NebulaVertices}
import com.vesoft.nebula.connector.{KeyPolicy, NebulaOptions, WriteMode}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.v2.writer.{DataWriter, WriterCommitMessage}
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer

class NebulaVertexWriter(nebulaOptions: NebulaOptions, vertexIndex: Int, schema: StructType)
    extends NebulaWriter(nebulaOptions)
    with DataWriter[InternalRow] {

  private val LOG = LoggerFactory.getLogger(this.getClass)

  val propNames   = NebulaExecutor.assignVertexPropNames(schema, vertexIndex, nebulaOptions.vidAsProp)
  val fieldTypMap = metaProvider.getTagSchema(nebulaOptions.spaceName, nebulaOptions.label)

  val policy = {
    if (nebulaOptions.vidPolicy.isEmpty) Option.empty
    else Option(KeyPolicy.withName(nebulaOptions.vidPolicy))
  }

  /** buffer to save batch vertices */
  var vertices: ListBuffer[NebulaVertex] = new ListBuffer()

  prepareSpace()

  /**
    * write one vertex row to buffer
    */
  override def write(row: InternalRow): Unit = {
    val vertex =
      NebulaExecutor.extraID(schema, row, vertexIndex, policy, isVidStringType)
    val values = NebulaExecutor.assignVertexPropValues(schema,
                                                       row,
                                                       vertexIndex,
                                                       nebulaOptions.vidAsProp,
                                                       fieldTypMap)
    val nebulaVertex = NebulaVertex(vertex, values)
    vertices.append(nebulaVertex)
    if (vertices.size >= nebulaOptions.batch) {
      execute()
    }
  }

  def execute(): Unit = {
    val nebulaVertices = NebulaVertices(propNames, vertices.toList, policy)
    val exec = if (nebulaOptions.writeMode == WriteMode.INSERT) {
      NebulaExecutor.toExecuteSentence(nebulaOptions.label, nebulaVertices)
    } else {
      nebulaVertices.values
        .map { vertex =>
          NebulaExecutor.toUpdateExecuteStatement(nebulaOptions.label,
                                                  nebulaVertices.propNames,
                                                  vertex)
        }
        .mkString(";")
    }
    vertices.clear()
    submit(exec)
  }

  override def commit(): WriterCommitMessage = {
    if (vertices.nonEmpty) {
      execute()
    }
    graphProvider.close()
    NebulaCommitMessage(failedExecs.toList)
  }

  override def abort(): Unit = {
    LOG.error("insert vertex task abort.")
    graphProvider.close()
  }
}
