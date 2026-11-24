package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.TableName

/**
 * The code the middle layer produces: one SQL script that creates the target tables exactly as the
 * contract defines them and loads them from the sources, runnable without AgentIO or an LLM
 * (`duckdb target.duckdb < etl.sql`).
 */
internal data class EtlScript(
    val createTables: List<String>,
    val loads: List<Load>,
    val header: List<String>,
) {
    data class Load(
        val table: TableName,
        val explanation: String,
        val insert: String,
    )

    fun render(): String =
        buildString {
            header.flatMap { it.lines() }.forEach { appendLine("-- $it".trimEnd()) }
            appendLine()
            appendLine("-- Target tables, with the keys and CHECK constraints of the contract")
            createTables.forEach { appendLine(it.trim().let { sql -> if (sql.endsWith(";")) sql else "$sql;" }) }
            loads.forEach { load ->
                appendLine()
                appendLine("-- ${load.table.value}")
                load.explanation
                    .lines()
                    .filter { it.isNotBlank() }
                    .forEach { appendLine("--   ${it.trim()}") }
                appendLine("${load.insert};")
            }
        }

    /**
     * Runs the script in a new, empty DuckDB and returns the rows each loaded table holds there, so the
     * script is proven to work on its own and not only inside the session that produced it.
     */
    fun replay(): Map<TableName, Long> {
        val replayed = DuckDbDatabaseEnvironment.fromStatements(createTables, loads.map { it.insert }, emptySet())
        return loads.associate { load ->
            val count = replayed.executeQuery(replayed.selectStatement("SELECT count(*) AS n FROM \"${load.table.value}\"").getOrThrow())
            load.table to ((count.records.single()[ColumnName("n")] as DataValue.LongValue).value)
        }
    }

    companion object {
        fun of(
            workspace: EtlWorkspace,
            loaded: List<LoadedTable>,
            header: List<String>,
        ): EtlScript =
            EtlScript(
                createTables = workspace.createTableStatements().values.toList(),
                loads =
                    loaded.map {
                        val table = workspace.targetTable(it.table)
                        Load(table, it.explanation, workspace.insertSql(table, it.selectSql))
                    },
                header = header,
            )
    }
}
