package io.github.mbbhalla.agentio.examples.text2sql.data

import io.github.mbbhalla.agentio.examples.text2sql.model.DatabaseEnvironment
import io.github.mbbhalla.agentio.examples.text2sql.model.Dataset
import io.github.mbbhalla.agentio.examples.text2sql.model.ExplainResult
import io.github.mbbhalla.agentio.examples.text2sql.model.TableInfo
import io.github.mbbhalla.agentio.examples.text2sql.model.TableName
import java.sql.Connection
import java.sql.DriverManager

object RetailDatabase : DatabaseEnvironment {

    private val connection: Connection by lazy {
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:").also { conn ->
            conn.createStatement().use { stmt ->
                RetailSchema.DDL_STATEMENTS.forEach { stmt.execute(it) }
                RetailSeedData.INSERTS.forEach { stmt.execute(it) }
            }
        }
    }

    override fun explain(sql: String): ExplainResult = try {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("EXPLAIN $sql").close()
        }
        ExplainResult.Success
    } catch (e: Exception) {
        ExplainResult.Failure(e.message ?: "Unknown SQL error")
    }

    override fun executeQuery(sql: String): Dataset = connection.createStatement().use { stmt ->
        Dataset.from(stmt.executeQuery(sql))
    }

    override fun listTables(): Set<TableName> = RetailSchema.TABLES.keys

    override fun getTableInfo(tableName: TableName): TableInfo =
        RetailSchema.TABLES[tableName]
            ?: throw IllegalArgumentException("Table '${tableName.value}' not found")
}
