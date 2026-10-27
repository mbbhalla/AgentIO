package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.model.DataValue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Database-specific rules needed to rewrite SQL safely. Parsing is shared (JSqlParser accepts a
 * superset of the common dialects); a dialect only supplies identifier semantics and literal
 * rendering. Adding a database means implementing this interface — nothing in [SqlAuthorizer]
 * changes.
 */
interface SqlDialect {
    val name: String

    /**
     * Folds an identifier part as written in SQL into the form the database stores it in, so that
     * references written differently but naming the same object compare equal.
     *
     * @param part the identifier text without surrounding quotes
     * @param quoted whether it was written with delimiting quotes
     */
    fun canonicalIdentifier(
        part: String,
        quoted: Boolean,
    ): String

    /** Renders [identifier] delimited so that it is taken literally (no case folding, keywords allowed). */
    fun quoteIdentifier(identifier: String): String

    /** Renders [value] as a SQL literal that is safe to embed in a statement. */
    fun renderLiteral(value: DataValue): String

    /**
     * The only way to obtain a [TableReference]. Parts are given as written in SQL (quotes included,
     * when present) and are canonicalised for this dialect.
     */
    fun tableReference(
        name: String,
        schema: String? = null,
        database: String? = null,
    ): TableReference =
        TableReference.canonical(
            database = database?.let(::canonicalPart),
            schema = schema?.let(::canonicalPart),
            name = canonicalPart(name),
        )

    private fun canonicalPart(written: String): String {
        val quoted = written.length >= 2 && written.first() == '"' && written.last() == '"'
        val raw = if (quoted) written.substring(1, written.length - 1).replace("\"\"", "\"") else written
        return canonicalIdentifier(raw, quoted)
    }
}

/**
 * Shared rendering for databases in the PostgreSQL family.
 *
 * Identifiers are delimited with double quotes (embedded quotes doubled). Timestamps are rendered as
 * `TIMESTAMP 'yyyy-MM-dd HH:mm:ss.SSSSSS'` in UTC.
 */
abstract class PostgresFamilyDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    override fun renderLiteral(value: DataValue): String =
        when (value) {
            is DataValue.StringValue -> quoteString(value.value)
            is DataValue.LongValue -> value.value.toString()
            is DataValue.DoubleValue -> {
                require(value.value.isFinite()) { "Cannot render non-finite double ${value.value} as a SQL literal" }
                value.value.toString()
            }
            is DataValue.BooleanValue -> if (value.value) "TRUE" else "FALSE"
            is DataValue.TimestampValue -> "TIMESTAMP '${formatTimestamp(value.value)}'"
            DataValue.NullValue -> "NULL"
        }

    /** Escapes [raw] and wraps it in single quotes according to the dialect's string-literal rules. */
    protected abstract fun quoteString(raw: String): String

    private fun formatTimestamp(iso: String): String {
        val instant =
            try {
                Instant.parse(iso)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("TimestampValue '$iso' is not an ISO-8601 instant", e)
            }
        return TIMESTAMP_FORMAT.format(instant.atOffset(ZoneOffset.UTC))
    }

    private companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
    }
}

/**
 * Amazon Redshift.
 *
 * - Identifiers are case-insensitive and stored lower-case, *including* quoted ones (unless the
 *   session option `enable_case_sensitive_identifier` is on, which this dialect does not model).
 * - String literals treat backslash as an escape character, so both `'` and `\` are doubled —
 *   the same rule `QUOTE_LITERAL` applies.
 */
object RedshiftSqlDialect : PostgresFamilyDialect() {
    override val name: String = "redshift"

    override fun canonicalIdentifier(
        part: String,
        quoted: Boolean,
    ): String = part.lowercase()

    override fun quoteString(raw: String): String = "'" + raw.replace("\\", "\\\\").replace("'", "''") + "'"
}

/**
 * DuckDB.
 *
 * - Identifiers are matched case-insensitively, quoted or not (DuckDB preserves the case it was
 *   created with but resolves references ignoring case).
 * - String literals follow the SQL standard: only `'` is doubled; backslash is an ordinary character.
 */
object DuckDbSqlDialect : PostgresFamilyDialect() {
    override val name: String = "duckdb"

    override fun canonicalIdentifier(
        part: String,
        quoted: Boolean,
    ): String = part.lowercase()

    override fun quoteString(raw: String): String = "'" + raw.replace("'", "''") + "'"
}
