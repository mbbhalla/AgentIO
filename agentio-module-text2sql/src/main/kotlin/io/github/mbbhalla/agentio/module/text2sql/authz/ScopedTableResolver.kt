package io.github.mbbhalla.agentio.module.text2sql.authz

import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SelectVisitor
import net.sf.jsqlparser.statement.select.SetOperationList
import net.sf.jsqlparser.statement.select.WithItem
import net.sf.jsqlparser.util.TablesNamesFinder

/**
 * Walks a parsed SELECT and reports every [Table] node that denotes a *base table* — i.e. one that
 * is not a reference to a common table expression visible at that point.
 *
 * JSqlParser's [TablesNamesFinder] keeps one flat list of CTE names for the whole statement, so a
 * CTE declared deep inside a sub-query would hide a same-named base table elsewhere. That is a
 * security hole for authorization, so this visitor tracks CTE visibility per scope, following the
 * standard rules:
 *
 *  - a CTE is visible to the query body of the `WITH` that declares it and to everything nested in it;
 *  - a non-recursive CTE is visible to *later* siblings in the same `WITH` list, not earlier ones;
 *  - a `RECURSIVE` CTE is additionally visible to itself.
 *
 * The visitor is single-use; call [resolve] once.
 */
internal class ScopedTableResolver(
    private val dialect: SqlDialect,
) : TablesNamesFinder<Void?>() {
    /** A base-table reference as it appears in the statement. */
    data class Occurrence(
        /** Live AST node; mutate it to rewrite the reference. */
        val table: Table,
        /** Name parts as written (quotes preserved), outermost first: `[database?, schema?, name]`. */
        val writtenParts: List<String>,
    )

    data class Resolution(
        val occurrences: List<Occurrence>,
        /** Every CTE name, table name and alias in the statement, canonicalised — for collision-free naming. */
        val identifiersInUse: Set<String>,
    )

    private val scopes = ArrayDeque<Set<String>>()
    private val occurrences = mutableListOf<Occurrence>()
    private val identifiers = mutableSetOf<String>()

    fun resolve(select: Select): Resolution {
        init(false)
        select.accept(this as SelectVisitor<Void?>, null)
        return Resolution(occurrences.toList(), identifiers.toSet())
    }

    // CTE declarations are traversed by [inCteScope]; the inherited flat handling is disabled.
    override fun <S> visit(
        withItem: WithItem<*>,
        context: S,
    ): Void? = null

    override fun <S> visit(
        plainSelect: PlainSelect,
        context: S,
    ): Void? {
        if (!plainSelect.intoTables.isNullOrEmpty()) {
            throw SqlAuthorizationException("SELECT ... INTO is not permitted")
        }
        return inCteScope(plainSelect) { super.visit(plainSelect, context) }
    }

    override fun <S> visit(
        list: SetOperationList,
        context: S,
    ): Void? = inCteScope(list) { super.visit(list, context) }

    override fun <S> visit(
        select: ParenthesedSelect,
        context: S,
    ): Void? {
        select.alias?.let { identifiers += canonical(it.name) }
        return inCteScope(select) { super.visit(select, context) }
    }

    override fun <S> visit(
        table: Table,
        context: S,
    ): Void? {
        // partItems is stored innermost-first: [name, schema, database, server]
        val parts = table.nameParts.toList()
        if (parts.size > MAX_NAME_PARTS || parts.any { it.isNullOrEmpty() }) {
            throw SqlAuthorizationException("Unsupported table reference '${table.fullyQualifiedName}'")
        }
        val written = parts.filterNotNull().reversed()
        table.alias?.let { identifiers += canonical(it.name) }
        identifiers += canonical(written.last())

        val isCteReference = written.size == 1 && scopes.any { canonical(written.single()) in it }
        if (!isCteReference) {
            occurrences += Occurrence(table, written)
        }
        return null
    }

    /**
     * Visits the `WITH` items of [select] with correct sibling visibility, then runs [body] with
     * all of them in scope.
     */
    private fun inCteScope(
        select: Select,
        body: () -> Void?,
    ): Void? {
        val declared = mutableSetOf<String>()
        for (item in select.withItemsList.orEmpty()) {
            val cteSelect =
                item.parenthesedStatement as? ParenthesedSelect
                    ?: throw SqlAuthorizationException("Data-modifying WITH clauses are not permitted")
            val own = canonical(item.aliasName)
            identifiers += own
            scopes.addLast(if (item.isRecursive) declared + own else declared.toSet())
            cteSelect.accept(this as SelectVisitor<Void?>, null)
            scopes.removeLast()
            declared += own
        }
        scopes.addLast(declared)
        try {
            return body()
        } finally {
            scopes.removeLast()
        }
    }

    private fun canonical(written: String): String = dialect.tableReference(name = written).name

    private companion object {
        const val MAX_NAME_PARTS = 3
    }
}
