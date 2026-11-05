package io.github.mbbhalla.agentio.data.authz

import io.github.mbbhalla.agentio.data.model.DataValue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Renders a [Criteria] tree to a DuckDB boolean expression. Every sub-expression is parenthesised so
 * precedence never depends on the parser; identifiers are quoted and literals escaped, which is what
 * makes the output injection-safe.
 */
internal object CriteriaRenderer {
    fun render(criteria: Criteria): String =
        when (criteria) {
            Criteria.AllRows -> "(1 = 1)"
            Criteria.NoRows -> "(1 = 0)"
            is Criteria.Compare ->
                "(${identifier(criteria.column.value)} ${criteria.operator.symbol} ${literal(criteria.value)})"
            is Criteria.In -> {
                val op = if (criteria.negated) "NOT IN" else "IN"
                "(${identifier(criteria.column.value)} $op (${criteria.values.joinToString(", ", transform = ::literal)}))"
            }
            is Criteria.IsNull -> {
                val op = if (criteria.negated) "IS NOT NULL" else "IS NULL"
                "(${identifier(criteria.column.value)} $op)"
            }
            is Criteria.And -> criteria.operands.joinToString(" AND ", prefix = "(", postfix = ")", transform = ::render)
            is Criteria.Or -> criteria.operands.joinToString(" OR ", prefix = "(", postfix = ")", transform = ::render)
            is Criteria.Not -> "(NOT ${render(criteria.operand)})"
        }

    /** Renders [name] as a quoted identifier, taken literally (keywords allowed, embedded quotes doubled). */
    fun identifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** String literals follow the SQL standard in DuckDB: only `'` is doubled; backslash is an ordinary character. */
    private fun literal(value: DataValue): String =
        when (value) {
            is DataValue.StringValue -> "'" + value.value.replace("'", "''") + "'"
            is DataValue.LongValue -> value.value.toString()
            is DataValue.DoubleValue -> {
                require(value.value.isFinite()) { "Cannot render non-finite double ${value.value} as a SQL literal" }
                value.value.toString()
            }
            is DataValue.BooleanValue -> if (value.value) "TRUE" else "FALSE"
            is DataValue.TimestampValue -> "TIMESTAMP '${utcTimestamp(value.value)}'"
            DataValue.NullValue -> "NULL"
        }

    private fun utcTimestamp(iso: String): String {
        val instant =
            try {
                Instant.parse(iso)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("TimestampValue '$iso' is not an ISO-8601 instant", e)
            }
        return TIMESTAMP_FORMAT.format(instant.atOffset(ZoneOffset.UTC))
    }

    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
}
