package io.github.mbbhalla.agentio.module.text2sql.authz

/**
 * Renders a [Criteria] tree to a SQL boolean expression. Every sub-expression is parenthesised so
 * precedence never depends on the database's parser; identifiers and literals go through the
 * [SqlDialect], which is what makes the output injection-safe.
 */
internal object CriteriaRenderer {
    fun render(
        criteria: Criteria,
        dialect: SqlDialect,
    ): String =
        when (criteria) {
            Criteria.AllRows -> "(1 = 1)"
            Criteria.NoRows -> "(1 = 0)"
            is Criteria.Compare ->
                "(${dialect.quoteIdentifier(criteria.column.value)} ${criteria.operator.symbol} " +
                    "${dialect.renderLiteral(criteria.value)})"
            is Criteria.In -> {
                val op = if (criteria.negated) "NOT IN" else "IN"
                val list = criteria.values.joinToString(", ") { dialect.renderLiteral(it) }
                "(${dialect.quoteIdentifier(criteria.column.value)} $op ($list))"
            }
            is Criteria.IsNull -> {
                val op = if (criteria.negated) "IS NOT NULL" else "IS NULL"
                "(${dialect.quoteIdentifier(criteria.column.value)} $op)"
            }
            is Criteria.And -> criteria.operands.joinToString(" AND ", prefix = "(", postfix = ")") { render(it, dialect) }
            is Criteria.Or -> criteria.operands.joinToString(" OR ", prefix = "(", postfix = ")") { render(it, dialect) }
            is Criteria.Not -> "(NOT ${render(criteria.operand, dialect)})"
        }
}
