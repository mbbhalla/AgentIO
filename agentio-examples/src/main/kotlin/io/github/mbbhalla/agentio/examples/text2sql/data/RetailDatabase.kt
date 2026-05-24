package io.github.mbbhalla.agentio.examples.text2sql.data

import io.github.mbbhalla.agentio.examples.text2sql.model.Dataset
import io.github.mbbhalla.agentio.examples.text2sql.model.ExplainResult
import io.github.mbbhalla.agentio.examples.text2sql.model.TableInfo
import java.sql.Connection
import java.sql.DriverManager

object RetailDatabase {

    val connection: Connection by lazy {
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:").also { conn ->
            conn.createStatement().use { stmt ->
                RetailSchema.DDL_STATEMENTS.forEach { stmt.execute(it) }
                RetailSeedData.INSERTS.forEach { stmt.execute(it) }
            }
        }
    }

    fun explain(sql: String): ExplainResult = try {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("EXPLAIN $sql").close()
        }
        ExplainResult.Success
    } catch (e: Exception) {
        ExplainResult.Failure(e.message ?: "Unknown SQL error")
    }

    fun executeQuery(sql: String): Dataset = connection.createStatement().use { stmt ->
        Dataset.from(stmt.executeQuery(sql))
    }

    fun listTables(): Set<String> = RetailSchema.TABLE_METADATA.keys

    fun getTableInfo(tableName: String): TableInfo? {
        val meta = RetailSchema.TABLE_METADATA[tableName] ?: return null
        return TableInfo(
            name = tableName,
            description = meta.description,
            columns = meta.columns,
        )
    }
}
