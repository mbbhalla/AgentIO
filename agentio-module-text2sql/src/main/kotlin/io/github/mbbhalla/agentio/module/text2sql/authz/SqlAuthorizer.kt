package io.github.mbbhalla.agentio.module.text2sql.authz

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.Alias
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.WithItem

/**
 * Optional database-side check of the rewritten SQL (e.g. `EXPLAIN` over JDBC). JSqlParser accepts
 * a superset of each dialect, so the only authority on *exact* syntax is the database itself.
 */
fun interface SqlValidator {
    fun validate(sql: String): Result<Unit>

    companion object {
        val NONE: SqlValidator = SqlValidator { Result.success(Unit) }
    }
}

/**
 * Rewrites a `SELECT` so that every base table it reads is replaced by a common table expression
 * that applies the caller's row-level [Criteria]:
 *
 * ```
 * SELECT * FROM inventory                            -- input
 * WITH authz_inventory AS (                          -- output
 *   SELECT * FROM inventory WHERE (("site" = 'TX') OR ("site" = 'CA'))
 * ) SELECT * FROM authz_inventory AS inventory
 * ```
 *
 * Guarantees, given a syntactically valid input:
 *  - the output parses ([AuthorizedSql] re-parses it on construction) and, if a [SqlValidator] is
 *    supplied, is accepted by the database;
 *  - every row produced by the output is also produced by the input (filtering is applied at the
 *    base-table boundary, before any join, aggregation or set operation);
 *  - only `SELECT` statements are accepted; `SELECT INTO`, data-modifying CTEs and multiple
 *    statements are rejected;
 *  - a base table without a policy fails the request unless the context opts into
 *    [UnlistedTablePolicy.ALLOW_ALL_ROWS];
 *  - CTE references are resolved per scope, so an inner `WITH` cannot shadow a base table to escape
 *    its policy.
 *
 * Table aliases are preserved; when a table had no alias its original (last-part) name is installed
 * as the alias so that qualified column references such as `inventory.site` keep resolving.
 *
 * The dialect is taken from the [AuthorizationContext], so an authorizer can never be paired with a
 * context built for a different database. Instances are stateless and safe to share.
 *
 * @param validator optional database-side check of the rewritten SQL; it must target the same
 *   database as the contexts this authorizer is used with.
 */
class SqlAuthorizer(
    private val validator: SqlValidator = SqlValidator.NONE,
) {
    fun authorize(
        sql: String,
        context: AuthorizationContext,
    ): Result<AuthorizedSql> =
        try {
            Result.success(rewrite(sql, context))
        } catch (e: SqlAuthorizationException) {
            Result.failure(e)
        } catch (e: JSQLParserException) {
            Result.failure(SqlAuthorizationException("SQL could not be parsed: ${e.message?.lineSequence()?.first()}", e))
        } catch (e: IllegalArgumentException) {
            Result.failure(SqlAuthorizationException(e.message ?: "Invalid input", e))
        }

    private fun rewrite(
        sql: String,
        context: AuthorizationContext,
    ): AuthorizedSql {
        val dialect = context.dialect
        val select = parseSingleSelect(sql)
        val resolution = ScopedTableResolver(dialect).resolve(select)

        val filtered = mutableMapOf<TableReference, MutableList<ScopedTableResolver.Occurrence>>()
        val unfiltered = mutableSetOf<TableReference>()
        for (occurrence in resolution.occurrences) {
            val reference = referenceOf(occurrence, context)
            when (context.policyFor(reference)) {
                null ->
                    when (context.unlistedTablePolicy) {
                        UnlistedTablePolicy.DENY ->
                            throw SqlAuthorizationException("Table '$reference' is not authorized")
                        UnlistedTablePolicy.ALLOW_ALL_ROWS -> unfiltered += reference
                    }
                else -> filtered.getOrPut(reference) { mutableListOf() } += occurrence
            }
        }

        val cteNames = CteNamer(resolution.identifiersInUse)
        val cteItems =
            filtered.map { (reference, occurrences) ->
                val cteName = cteNames.nameFor(reference)
                val criteria = checkNotNull(context.policyFor(reference))
                val cte = buildCte(cteName, occurrences.first().table.fullyQualifiedName, criteria, dialect)
                occurrences.forEach { rewriteOccurrence(it.table, cteName) }
                cte
            }
        prependWithItems(select, cteItems)

        // AuthorizedSql re-parses on construction, so an unparseable rewrite can never escape.
        val authorized =
            AuthorizedSql(
                sql = select.toString(),
                filteredTables = filtered.keys.toSet(),
                unfilteredTables = unfiltered.toSet(),
            )
        validator.validate(authorized.sql).onFailure {
            throw SqlAuthorizationException("Rewritten SQL rejected by ${dialect.name} validator: ${it.message}", it)
        }
        return authorized
    }

    private fun parseSingleSelect(sql: String): Select {
        require(sql.isNotBlank()) { "SQL must not be blank" }
        val statements = CCJSqlParserUtil.parseStatements(sql).statements
        if (statements.size != 1) {
            throw SqlAuthorizationException("Exactly one statement is required, found ${statements.size}")
        }
        return statements.single() as? Select
            ?: throw SqlAuthorizationException("Only SELECT statements can be authorized")
    }

    private fun referenceOf(
        occurrence: ScopedTableResolver.Occurrence,
        context: AuthorizationContext,
    ): TableReference {
        val parts = occurrence.writtenParts
        val written =
            context.dialect.tableReference(
                name = parts.last(),
                schema = parts.getOrNull(parts.size - SCHEMA_OFFSET),
                database = parts.getOrNull(parts.size - DATABASE_OFFSET),
            )
        val defaultSchema = context.defaultSchema // already canonical
        return if (written.schema == null && defaultSchema != null) {
            TableReference.canonical(database = null, schema = defaultSchema, name = written.name)
        } else {
            written
        }
    }

    private fun buildCte(
        cteName: String,
        writtenTableName: String,
        criteria: Criteria,
        dialect: SqlDialect,
    ): WithItem<ParenthesedSelect> {
        val where = if (criteria == Criteria.AllRows) "" else " WHERE ${CriteriaRenderer.render(criteria, dialect)}"
        val body = CCJSqlParserUtil.parse("SELECT * FROM $writtenTableName$where") as Select
        return WithItem(ParenthesedSelect().withSelect(body), Alias(cteName, false))
    }

    private fun rewriteOccurrence(
        table: Table,
        cteName: String,
    ) {
        val alias = table.alias ?: Alias(table.name, true)
        table.nameParts.clear()
        table.namePartDelimiters.clear()
        table.name = cteName
        table.alias = alias
    }

    private fun prependWithItems(
        select: Select,
        cteItems: List<WithItem<*>>,
    ) {
        if (cteItems.isEmpty()) return
        val existing = select.withItemsList.orEmpty()
        // RECURSIVE is a property of the WITH clause and must directly follow the keyword.
        if (existing.any { it.isRecursive }) {
            existing.forEach { it.isRecursive = false }
            cteItems.first().isRecursive = true
        }
        select.withItemsList = cteItems + existing
    }

    /** Allocates CTE names that collide with nothing already used in the statement. */
    private class CteNamer(
        inUse: Set<String>,
    ) {
        private val taken = inUse.toMutableSet()

        fun nameFor(reference: TableReference): String {
            val base = "authz_" + reference.toString().lowercase().replace(NON_IDENTIFIER, "_")
            val name =
                if (base !in taken) base else generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it !in taken }
            taken += name
            return name
        }

        private companion object {
            val NON_IDENTIFIER = Regex("[^a-z0-9_]")
        }
    }

    private companion object {
        const val SCHEMA_OFFSET = 2
        const val DATABASE_OFFSET = 3
    }
}
