package io.github.mbbhalla.agentio.examples.etl

import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.InsertSqlStatement
import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue
import io.github.mbbhalla.agentio.data.model.Dataset
import io.github.mbbhalla.agentio.data.model.DomainModel
import io.github.mbbhalla.agentio.data.model.TableName
import io.github.mbbhalla.agentio.examples.etl.SourceAccessGuard.Companion.sqlStringLiteral
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.useLines

/**
 * The target database built from a [DomainModel], the [SourceFiles] that feed it, and every operation
 * the agents' tools perform on them. All SQL that agents write passes [SourceAccessGuard] and DuckDB's
 * own validation before it runs; everything else here is SQL this class builds from validated names.
 *
 * Operations are serialized: an agent may call several tools in one turn, and a trial load inserts and
 * deletes rows that a concurrent query must not see.
 */
internal class EtlWorkspace(
    val model: DomainModel,
    val sources: SourceFiles,
) {
    val env: DuckDbDatabaseEnvironment = DuckDbDatabaseEnvironment.fromDomainModel(model)
    private val guard = SourceAccessGuard(env, sources)
    private val lock = ReentrantLock()

    /** The target table called [name], case-insensitively. */
    fun targetTable(name: String): TableName =
        runCatching { TableName(name.trim().lowercase()) }
            .getOrNull()
            ?.takeIf { it in model.tables }
            ?: throw IllegalArgumentException("Unknown target table '$name'; list_target_tables names them")

    fun targetTables(): List<TargetTableSummary> =
        model.tables.values
            .sortedBy { it.name.value }
            .map { TargetTableSummary(it.name.value, it.description, it.columns.size) }

    fun describe(name: String): TargetTableDetail =
        lock.withLock {
            val table = targetTable(name)
            val info = env.getTableInfo(table)
            TargetTableDetail(
                name = table.value,
                description = info.description,
                rowCount = rowCount(table),
                columns = info.columns.map { TargetColumn(it.name.value, it.type, it.nullable, it.primaryKey, it.description) },
                ddl = createTableStatements().getValue(table),
            )
        }

    /** DuckDB's sniffer on a source file, its first raw lines, and its first rows read as text. */
    fun profile(fileName: String): SourceProfile =
        lock.withLock {
            val path = sources.path(fileName)
            val file = sqlStringLiteral(path.toString())
            val dialect =
                query(
                    "SELECT Delimiter AS delimiter, CAST(SkipRows AS BIGINT) AS skip_rows, HasHeader AS has_header, " +
                        "DateFormat AS date_format, TimestampFormat AS timestamp_format FROM sniff_csv($file)",
                ).records.single()
            val columns =
                query("SELECT c.name AS name, c.type AS type FROM (SELECT unnest(Columns) AS c FROM sniff_csv($file))")
                    .records
                    .map { SourceColumn(it.text("name").orEmpty(), it.text("type").orEmpty()) }
            SourceProfile(
                fileName = fileName,
                path = path.toString(),
                firstLines = path.useLines { lines -> lines.take(RAW_LINES).toList() },
                delimiter = dialect.text("delimiter").orEmpty(),
                skipRows = dialect.long("skip_rows"),
                hasHeader = (dialect[ColumnName("has_header")] as? DataValue.BooleanValue)?.value ?: false,
                dateFormat = dialect.text("date_format"),
                timestampFormat = dialect.text("timestamp_format"),
                columns = columns,
                rowCount = scalar("SELECT count(*) AS n FROM read_csv($file, all_varchar = true)"),
                sampleRows = jsonRows("SELECT * FROM read_csv($file, all_varchar = true) LIMIT $SAMPLE_ROWS"),
            )
        }

    /** Runs an agent's SELECT, which may read source files and target tables, and returns its first [maxRows] rows. */
    fun query(
        sql: String,
        maxRows: Int,
    ): QueryResult =
        lock.withLock {
            guard.check(sql).getOrThrow()
            val body = body(sql)
            QueryResult(
                rowCount = scalar("SELECT count(*) AS n FROM (\n$body\n)"),
                rows = jsonRows("SELECT * FROM (\n$body\n) LIMIT $maxRows"),
            )
        }

    /**
     * Inserts [selectSql] into [table], reports what arrived, and deletes it again, so the agent learns
     * whether DuckDB and the contract accept its SQL without the table changing. [references] whose
     * `fromTable` is [table] are checked for orphaned values.
     */
    fun tryLoad(
        table: String,
        selectSql: String,
        references: List<Reference>,
    ): LoadTrial =
        lock.withLock {
            val target = targetTable(table)
            val inserted = insert(target, selectSql)
            try {
                LoadTrial(
                    table = target.value,
                    insertedRows = inserted,
                    nullCounts = nullCounts(target),
                    orphanedReferences =
                        references
                            .filter { targetTable(it.fromTable) == target }
                            .mapNotNull { reference -> orphans(reference).takeIf { it > 0 }?.let { "$reference: $it orphaned rows" } },
                    sampleRows = jsonRows("SELECT * FROM ${target.quoted()} LIMIT $TRIAL_SAMPLE_ROWS"),
                )
            } finally {
                env.executeUpdate(env.deleteStatement("DELETE FROM ${target.quoted()}").getOrThrow())
            }
        }

    /** Inserts [selectSql] into the empty [table] for good and returns the number of rows. */
    fun load(
        table: String,
        selectSql: String,
    ): Int = lock.withLock { insert(targetTable(table), selectSql) }

    /** Non-null values of `fromTable.fromColumn` with no equal value in `toTable.toColumn`. */
    fun orphans(reference: Reference): Long {
        val from = targetTable(reference.fromTable)
        val to = targetTable(reference.toTable)
        val fromColumn = column(from, reference.fromColumn)
        val toColumn = column(to, reference.toColumn)
        return scalar(
            "SELECT count(*) AS n FROM ${from.quoted()} AS c WHERE c.$fromColumn IS NOT NULL " +
                "AND NOT EXISTS (SELECT 1 FROM ${to.quoted()} AS p WHERE p.$toColumn = c.$fromColumn)",
        )
    }

    /** The column of [table] called [name], case-insensitively, as a quoted identifier. */
    fun column(
        table: TableName,
        name: String,
    ): String {
        val columns = env.getTableInfo(table).columns
        val column =
            columns.firstOrNull { it.name.value == name.trim().lowercase() }
                ?: throw IllegalArgumentException("Table '${table.value}' has no column '$name'; it has ${columns.map { it.name.value }}")
        return "\"${column.name.value}\""
    }

    fun rowCount(table: TableName): Long = scalar("SELECT count(*) AS n FROM ${table.quoted()}")

    /** Each table's `CREATE TABLE` as DuckDB holds it, with keys and checks, in the model's creation order. */
    fun createTableStatements(): Map<TableName, String> {
        val byName =
            query("SELECT table_name, sql FROM duckdb_tables()").records.associate {
                TableName(it.text("table_name").orEmpty()) to it.text("sql").orEmpty()
            }
        return model.creationOrder.associate { it.name to byName.getValue(it.name) }
    }

    /** The statement that loads [selectSql] into [table]: columns match by name, and missing ones are NULL. */
    fun insertSql(
        table: TableName,
        selectSql: String,
    ): String = "INSERT INTO ${table.quoted()} BY NAME SELECT * FROM (\n${body(selectSql)}\n)"

    private fun insert(
        table: TableName,
        selectSql: String,
    ): Int {
        guard.check(selectSql).getOrThrow()
        val existing = rowCount(table)
        require(existing == 0L) { "Table '${table.value}' already holds $existing rows" }
        val statement: InsertSqlStatement =
            env
                .insertStatement(insertSql(table, selectSql))
                .getOrElse { throw IllegalArgumentException("DuckDB rejects the load of '${table.value}': ${it.message}", it) }
        return try {
            env.executeUpdate(statement).also { LOG.info("Inserted {} rows into {}", it, table.value) }
        } catch (e: SQLException) {
            throw IllegalArgumentException("DuckDB rejected rows for '${table.value}': ${e.message}", e)
        }
    }

    private fun nullCounts(table: TableName): Map<String, Long> {
        val columns = env.getTableInfo(table).columns.map { it.name.value }
        val counts =
            query(
                "SELECT ${columns.joinToString { "count(*) - count(\"$it\") AS \"$it\"" }} FROM ${table.quoted()}",
            ).records.single()
        return columns.associateWith { counts.long(it) }.filterValues { it > 0 }
    }

    private fun query(sql: String): Dataset =
        env.executeQuery(env.selectStatement(sql).getOrElse { throw IllegalArgumentException(it.message, it) })

    private fun scalar(sql: String): Long = query(sql).records.single().long("n")

    /** Rows as JSON objects, so any column names, including the headers of a CSV, come through. */
    private fun jsonRows(sql: String): List<String> =
        query("SELECT CAST(to_json(q) AS VARCHAR) AS row FROM (\n$sql\n) AS q").records.mapNotNull { it.text("row") }

    private fun Dataset.Record.text(column: String): String? = (this[ColumnName(column)] as? DataValue.StringValue)?.value

    private fun Dataset.Record.long(column: String): Long =
        when (val value = this[ColumnName(column)]) {
            is DataValue.LongValue -> value.value
            is DataValue.NullValue, null -> 0
            else -> throw IllegalStateException("Expected a count in '$column', found $value")
        }

    private fun TableName.quoted(): String = "\"$value\""

    companion object {
        private val LOG = LoggerFactory.getLogger(EtlWorkspace::class.java)

        /** Raw lines of a source shown to the agent, enough to see a preamble before the header. */
        private const val RAW_LINES = 6

        /** Rows of a source shown in its profile. */
        private const val SAMPLE_ROWS = 5

        /** Rows of a trial load shown back to the agent. */
        private const val TRIAL_SAMPLE_ROWS = 3

        /** An agent's statement without trailing semicolons, so it can be wrapped. */
        fun body(sql: String): String = sql.trim().trimEnd(';').trim()
    }
}
