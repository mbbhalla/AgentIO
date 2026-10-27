package io.github.mbbhalla.agentio.module.text2sql.authz

import io.github.mbbhalla.agentio.data.model.ColumnName
import io.github.mbbhalla.agentio.data.model.DataValue

/**
 * Binary comparison operators supported by [Criteria.Compare].
 *
 * The [symbol] is the dialect-neutral SQL spelling; every supported [SqlDialect] accepts it.
 */
enum class ComparisonOperator(
    val symbol: String,
) {
    EQUALS("="),
    NOT_EQUALS("<>"),
    LESS_THAN("<"),
    LESS_THAN_OR_EQUAL("<="),
    GREATER_THAN(">"),
    GREATER_THAN_OR_EQUAL(">="),
    LIKE("LIKE"),
}

/**
 * A composable row-level predicate attached to a table by an [AuthorizationContext].
 *
 * Criteria are pure data: they describe *which rows* a caller may see and are rendered to a SQL
 * `WHERE` expression by [SqlAuthorizer] using the target [SqlDialect] for identifier quoting and
 * literal escaping. Because column names are [ColumnName]s and values are [DataValue]s, a
 * `Criteria` can never carry arbitrary SQL text — the rendered predicate is injection-safe by
 * construction.
 *
 * Leaves:
 *  - [AllRows] / [NoRows] — constant predicates (`1 = 1` / `1 = 0`)
 *  - [Compare] — `column <op> literal`
 *  - [In] — `column [NOT] IN (literal, ...)`
 *  - [IsNull] — `column IS [NOT] NULL`
 *
 * Combinators: [And], [Or], [Not].
 */
sealed interface Criteria {
    /** Every row is visible. */
    data object AllRows : Criteria

    /** No row is visible. The table may still be referenced, but yields an empty result. */
    data object NoRows : Criteria

    data class Compare(
        val column: ColumnName,
        val operator: ComparisonOperator,
        val value: DataValue,
    ) : Criteria {
        init {
            require(value !is DataValue.NullValue) {
                "Comparing against NULL is always unknown; use Criteria.IsNull for column '${column.value}'"
            }
        }
    }

    data class In(
        val column: ColumnName,
        val values: List<DataValue>,
        val negated: Boolean = false,
    ) : Criteria {
        init {
            require(values.isNotEmpty()) { "IN list for column '${column.value}' must not be empty" }
            require(values.none { it is DataValue.NullValue }) {
                "IN list for column '${column.value}' must not contain NULL; use Criteria.IsNull"
            }
        }
    }

    data class IsNull(
        val column: ColumnName,
        val negated: Boolean = false,
    ) : Criteria

    data class And(
        val operands: List<Criteria>,
    ) : Criteria {
        init {
            require(operands.isNotEmpty()) { "AND requires at least one operand" }
        }
    }

    data class Or(
        val operands: List<Criteria>,
    ) : Criteria {
        init {
            require(operands.isNotEmpty()) { "OR requires at least one operand" }
        }
    }

    data class Not(
        val operand: Criteria,
    ) : Criteria

    companion object {
        fun and(
            first: Criteria,
            vararg rest: Criteria,
        ): Criteria = And(listOf(first) + rest)

        fun or(
            first: Criteria,
            vararg rest: Criteria,
        ): Criteria = Or(listOf(first) + rest)

        fun not(operand: Criteria): Criteria = Not(operand)
    }
}

// ---------------------------------------------------------------------------------------------
// Readability DSL: ColumnName("site") eq StringValue("TX")
// ---------------------------------------------------------------------------------------------

infix fun ColumnName.eq(value: DataValue): Criteria = Criteria.Compare(this, ComparisonOperator.EQUALS, value)

infix fun ColumnName.notEq(value: DataValue): Criteria = Criteria.Compare(this, ComparisonOperator.NOT_EQUALS, value)

infix fun ColumnName.lt(value: DataValue): Criteria = Criteria.Compare(this, ComparisonOperator.LESS_THAN, value)

infix fun ColumnName.lte(value: DataValue): Criteria = Criteria.Compare(this, ComparisonOperator.LESS_THAN_OR_EQUAL, value)

infix fun ColumnName.gt(value: DataValue): Criteria = Criteria.Compare(this, ComparisonOperator.GREATER_THAN, value)

infix fun ColumnName.gte(value: DataValue): Criteria = Criteria.Compare(this, ComparisonOperator.GREATER_THAN_OR_EQUAL, value)

infix fun ColumnName.like(value: DataValue): Criteria = Criteria.Compare(this, ComparisonOperator.LIKE, value)

infix fun ColumnName.isIn(values: List<DataValue>): Criteria = Criteria.In(this, values)

infix fun ColumnName.notIn(values: List<DataValue>): Criteria = Criteria.In(this, values, negated = true)

fun ColumnName.isNull(): Criteria = Criteria.IsNull(this)

fun ColumnName.isNotNull(): Criteria = Criteria.IsNull(this, negated = true)
