package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.DatabaseEnvironmentSnapshot
import io.github.mbbhalla.agentio.data.model.Dataset
import io.github.mbbhalla.agentio.data.model.ExplainResult
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName
import kotlinx.serialization.json.JsonObject

/**
 * A database that agents and tools read. Statements are validated by an environment and can only be
 * executed on that same environment, so any number of environments can coexist in one JVM.
 */
abstract class DatabaseEnvironment {
    abstract val snapshot: DatabaseEnvironmentSnapshot?

    abstract fun listTables(): Set<TableName>

    abstract fun getTableInfo(tableName: TableName): TableInfo

    abstract fun explain(sql: String): ExplainResult

    abstract fun statementType(sql: String): StatementType

    /**
     * The only way to obtain a [SelectSqlStatement]: [sql] must pass `EXPLAIN` on this environment and
     * be exactly one read-only `SELECT`. Fails with [IllegalArgumentException] otherwise.
     */
    fun selectStatement(sql: String): Result<SelectSqlStatement> = validate(sql, StatementType.SELECT).map { SelectSqlStatement(it, this) }

    /** The only way to obtain an [InsertSqlStatement]; see [selectStatement]. */
    fun insertStatement(sql: String): Result<InsertSqlStatement> = validate(sql, StatementType.INSERT).map { InsertSqlStatement(it, this) }

    /** The only way to obtain an [UpdateSqlStatement]; see [selectStatement]. */
    fun updateStatement(sql: String): Result<UpdateSqlStatement> = validate(sql, StatementType.UPDATE).map { UpdateSqlStatement(it, this) }

    /** The only way to obtain a [DeleteSqlStatement]; see [selectStatement]. */
    fun deleteStatement(sql: String): Result<DeleteSqlStatement> = validate(sql, StatementType.DELETE).map { DeleteSqlStatement(it, this) }

    /**
     * Runs [sql] on this environment.
     *
     * @throws IllegalArgumentException if [sql] was validated by a different environment
     */
    fun executeQuery(sql: SelectSqlStatement): Dataset {
        require(sql.environment === this) { "SelectSqlStatement was validated by a different DatabaseEnvironment" }
        return executeValidatedQuery(sql.value)
    }

    /** Runs a `SELECT` that [selectStatement] has validated on this environment. */
    protected abstract fun executeValidatedQuery(sql: String): Dataset

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

    private fun validate(
        sql: String,
        expected: StatementType,
    ): Result<String> {
        val explained = explain(sql)
        return when {
            explained is ExplainResult.Failure ->
                Result.failure(IllegalArgumentException("Invalid ${expected.name} SQL: ${explained.error}"))
            statementType(sql) != expected ->
                Result.failure(IllegalArgumentException("Not ${expected.withArticle()} statement"))
            else -> Result.success(sql)
        }
    }

    private fun StatementType.withArticle(): String =
        when (this) {
            StatementType.INSERT, StatementType.UPDATE, StatementType.UNKNOWN -> "an $name"
            StatementType.SELECT, StatementType.DELETE -> "a $name"
        }
}
