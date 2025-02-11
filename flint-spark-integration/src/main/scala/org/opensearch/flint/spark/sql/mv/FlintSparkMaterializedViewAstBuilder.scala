/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.flint.spark.sql.mv

import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`

import org.antlr.v4.runtime.tree.RuleNode
import org.opensearch.flint.spark.FlintSpark
import org.opensearch.flint.spark.FlintSpark.RefreshMode
import org.opensearch.flint.spark.mv.FlintSparkMaterializedView
import org.opensearch.flint.spark.sql.{FlintSparkSqlCommand, FlintSparkSqlExtensionsVisitor, SparkSqlAstBuilder}
import org.opensearch.flint.spark.sql.FlintSparkSqlAstBuilder.{getFullTableName, getSqlText}
import org.opensearch.flint.spark.sql.FlintSparkSqlExtensionsParser._

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.catalyst.plans.logical.Command
import org.apache.spark.sql.types.StringType

/**
 * Flint Spark AST builder that builds Spark command for Flint materialized view statement.
 */
trait FlintSparkMaterializedViewAstBuilder extends FlintSparkSqlExtensionsVisitor[AnyRef] {
  self: SparkSqlAstBuilder =>

  override def visitCreateMaterializedViewStatement(
      ctx: CreateMaterializedViewStatementContext): Command = {
    FlintSparkSqlCommand() { flint =>
      val mvName = getFullTableName(flint, ctx.mvName)
      val query = getSqlText(ctx.query)

      val mvBuilder = flint
        .materializedView()
        .name(mvName)
        .query(query)

      val ignoreIfExists = ctx.EXISTS() != null
      val indexOptions = visitPropertyList(ctx.propertyList())
      mvBuilder
        .options(indexOptions)
        .create(ignoreIfExists)

      // Trigger auto refresh if enabled
      if (indexOptions.autoRefresh()) {
        val flintIndexName = getFlintIndexName(flint, ctx.mvName)
        flint.refreshIndex(flintIndexName, RefreshMode.INCREMENTAL)
      }
      Seq.empty
    }
  }

  override def visitRefreshMaterializedViewStatement(
      ctx: RefreshMaterializedViewStatementContext): Command = {
    FlintSparkSqlCommand() { flint =>
      val flintIndexName = getFlintIndexName(flint, ctx.mvName)
      flint.refreshIndex(flintIndexName, RefreshMode.FULL)
      Seq.empty
    }
  }

  override def visitShowMaterializedViewStatement(
      ctx: ShowMaterializedViewStatementContext): Command = {
    val outputSchema = Seq(
      AttributeReference("materialized_view_name", StringType, nullable = false)())

    FlintSparkSqlCommand(outputSchema) { flint =>
      val catalogDbName =
        ctx.catalogDb.parts
          .map(part => part.getText)
          .mkString("_")
      val indexNamePattern = s"flint_${catalogDbName}_*"
      flint
        .describeIndexes(indexNamePattern)
        .collect { case mv: FlintSparkMaterializedView =>
          // MV name must be qualified when metadata created
          Row(mv.mvName.split('.').drop(2).mkString("."))
        }
    }
  }

  override def visitDescribeMaterializedViewStatement(
      ctx: DescribeMaterializedViewStatementContext): Command = {
    val outputSchema = Seq(
      AttributeReference("output_col_name", StringType, nullable = false)(),
      AttributeReference("data_type", StringType, nullable = false)())

    FlintSparkSqlCommand(outputSchema) { flint =>
      val flintIndexName = getFlintIndexName(flint, ctx.mvName)
      flint
        .describeIndex(flintIndexName)
        .map { case mv: FlintSparkMaterializedView =>
          mv.outputSchema.map { case (colName, colType) =>
            Row(colName, colType)
          }.toSeq
        }
        .getOrElse(Seq.empty)
    }
  }

  override def visitDropMaterializedViewStatement(
      ctx: DropMaterializedViewStatementContext): Command = {
    FlintSparkSqlCommand() { flint =>
      flint.deleteIndex(getFlintIndexName(flint, ctx.mvName))
      Seq.empty
    }
  }

  private def getFlintIndexName(flint: FlintSpark, mvNameCtx: RuleNode): String = {
    val fullMvName = getFullTableName(flint, mvNameCtx)
    FlintSparkMaterializedView.getFlintIndexName(fullMvName)
  }
}
