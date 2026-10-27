package io.github.mbbhalla.agentio.module.text2sql.authz

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.Select

/**
 * A base table in *canonical* form for a [SqlDialect] (for Redshift: lower-cased), so a reference
 * written as `Supply.Inventory` and one written as `"supply"."inventory"` compare equal.
 *
 * The constructor is private: the only way to obtain an instance is [SqlDialect.tableReference],
 * which guarantees canonical form. A non-canonical reference therefore cannot exist.
 *
 * A policy keyed on a reference with a `null` [schema] applies to a table with that [name] in *any*
 * schema; a reference with a `null` [database] applies to that `schema.name` in *any* database.
 */
@ConsistentCopyVisibility
data class TableReference private constructor(
    val database: String?,
    val schema: String?,
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "Table name must not be blank" }
        require(schema?.isNotBlank() ?: true) { "Schema must be null or non-blank" }
        require(database?.isNotBlank() ?: true) { "Database must be null or non-blank" }
        require(database == null || schema != null) { "A database-qualified reference must also have a schema" }
    }

    /** Successively less specific references used when looking up a policy. */
    internal fun lookupCandidates(): List<TableReference> =
        buildList {
            add(this@TableReference)
            if (database != null) add(TableReference(null, schema, name))
            if (schema != null) add(TableReference(null, null, name))
        }

    override fun toString(): String = listOfNotNull(database, schema, name).joinToString(".")

    internal companion object {
        /** For [SqlDialect] only: parts must already be canonical. */
        fun canonical(
            database: String?,
            schema: String?,
            name: String,
        ): TableReference = TableReference(database, schema, name)
    }
}

/** What [SqlAuthorizer] does when a query references a base table that has no policy. */
enum class UnlistedTablePolicy {
    /** Fail the authorization (recommended; fail closed). */
    DENY,

    /** Leave the reference untouched — the caller may read every row of the table. */
    ALLOW_ALL_ROWS,
}

/**
 * Row-level authorization rules for one caller against one [SqlDialect].
 *
 * Invariants enforced at construction: every policy key is canonical for [dialect] (so a key built
 * for another dialect is rejected rather than silently never matching) and [defaultSchema] is stored
 * in canonical form.
 *
 * @property policies table → predicate the caller is restricted to. A missing table is governed by
 *   [unlistedTablePolicy].
 * @property defaultSchema schema assumed for *unqualified* table references in the SQL (e.g. the
 *   Redshift `search_path` head). When `null`, unqualified references only match schema-less
 *   policies.
 */
@ConsistentCopyVisibility
data class AuthorizationContext private constructor(
    val dialect: SqlDialect,
    val policies: Map<TableReference, Criteria>,
    val defaultSchema: String?,
    val unlistedTablePolicy: UnlistedTablePolicy,
) {
    init {
        policies.keys.forEach { key ->
            require(dialect.tableReference(name = key.name, schema = key.schema, database = key.database) == key) {
                "Policy key '$key' is not canonical for dialect '${dialect.name}'"
            }
        }
    }

    /**
     * Finds the most specific policy for [reference] (exact → schema-level → name-only).
     * Returns `null` when none applies.
     */
    fun policyFor(reference: TableReference): Criteria? =
        reference
            .lookupCandidates()
            .firstNotNullOfOrNull { policies[it] }

    companion object {
        operator fun invoke(
            dialect: SqlDialect,
            policies: Map<TableReference, Criteria>,
            defaultSchema: String? = null,
            unlistedTablePolicy: UnlistedTablePolicy = UnlistedTablePolicy.DENY,
        ): AuthorizationContext =
            AuthorizationContext(
                dialect = dialect,
                policies = policies,
                defaultSchema = defaultSchema?.let { dialect.canonicalIdentifier(it, quoted = false) },
                unlistedTablePolicy = unlistedTablePolicy,
            )
    }
}

/**
 * The outcome of a successful [SqlAuthorizer.authorize].
 *
 * Correct by construction: the constructor is internal, so an instance can only originate from
 * [SqlAuthorizer] (which is what makes "honours the context" true), and [sql] is re-parsed on
 * construction, so an instance can never hold anything other than a single `SELECT`.
 */
@ConsistentCopyVisibility
data class AuthorizedSql internal constructor(
    /** Rewritten SQL, syntactically valid for the dialect, that honours the [AuthorizationContext]. */
    val sql: String,
    /** Base tables that were wrapped in a filtering CTE. */
    val filteredTables: Set<TableReference>,
    /** Base tables left untouched because of [UnlistedTablePolicy.ALLOW_ALL_ROWS]. */
    val unfilteredTables: Set<TableReference>,
) {
    init {
        require(sql.isNotBlank()) { "Authorized SQL must not be blank" }
        val statement =
            try {
                CCJSqlParserUtil.parse(sql)
            } catch (e: JSQLParserException) {
                throw IllegalArgumentException("Authorized SQL does not parse: ${e.message?.lineSequence()?.first()}", e)
            }
        require(statement is Select) { "Authorized SQL must be a SELECT, was ${statement::class.simpleName}" }
        require(filteredTables.none { it in unfilteredTables }) {
            "A table cannot be both filtered and unfiltered: ${filteredTables intersect unfilteredTables}"
        }
    }
}

/** Raised (inside a `Result.failure`) when SQL cannot be authorized. */
class SqlAuthorizationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
