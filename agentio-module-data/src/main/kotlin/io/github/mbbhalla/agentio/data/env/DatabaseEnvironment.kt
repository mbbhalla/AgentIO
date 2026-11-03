package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.DatabaseEnvironmentSnapshot
import io.github.mbbhalla.agentio.data.model.Dataset
import io.github.mbbhalla.agentio.data.model.ExplainResult
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName
import kotlinx.serialization.json.JsonObject

abstract class DatabaseEnvironment {
    abstract val snapshot: DatabaseEnvironmentSnapshot?

    abstract fun listTables(): Set<TableName>

    abstract fun getTableInfo(tableName: TableName): TableInfo

    abstract fun explain(sql: String): ExplainResult

    abstract fun statementType(sql: String): StatementType

    abstract fun executeQuery(sql: SelectSqlStatement): Dataset

    /**
     * Parses [sql] with DuckDB's own parser (`json_serialize_sql`) and returns the parse tree of its
     * single read-only `SELECT`. Nothing is bound or executed, so the tables it names need not exist.
     *
     * @throws IllegalArgumentException if [sql] is not exactly one `SELECT`, or if it contains a
     *   data-modifying query such as `WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d`
     */
    abstract fun parseSelect(sql: String): JsonObject

    /** Renders a parse tree in the form returned by [parseSelect] back to SQL (`json_deserialize_sql`). */
    abstract fun renderSelect(query: JsonObject): String

    companion object {
        @Volatile
        var current: DatabaseEnvironment? = null
            private set

        fun activate(env: DatabaseEnvironment) {
            current = env
        }
    }
}
