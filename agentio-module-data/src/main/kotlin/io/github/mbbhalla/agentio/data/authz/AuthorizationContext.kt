package io.github.mbbhalla.agentio.data.authz

import io.github.mbbhalla.agentio.data.env.SelectSqlStatement
import io.github.mbbhalla.agentio.data.model.TableName

/** What [SqlAuthorizer] does when a query reads a table that has no policy. */
enum class UnlistedTablePolicy {
    /** Fail the authorization (recommended; fail closed). */
    DENY,

    /** Leave the reference untouched — the caller may read every row of the table. */
    ALLOW_ALL_ROWS,
}

/**
 * Row-level authorization rules for one caller.
 *
 * @property policies table → predicate the caller is restricted to. A table without a policy is
 *   governed by [unlistedTablePolicy].
 */
data class AuthorizationContext(
    val policies: Map<TableName, Criteria>,
    val unlistedTablePolicy: UnlistedTablePolicy = UnlistedTablePolicy.DENY,
)

/**
 * The outcome of a successful [SqlAuthorizer.authorize]. The constructor is internal, so an instance
 * can only originate from the authorizer.
 */
@ConsistentCopyVisibility
data class AuthorizedSql internal constructor(
    /** The rewritten query that honours the [AuthorizationContext]. */
    val sql: SelectSqlStatement,
    /** Tables that were wrapped in a filtering CTE. */
    val filteredTables: Set<TableName>,
    /** Tables left untouched because of [UnlistedTablePolicy.ALLOW_ALL_ROWS]. */
    val unfilteredTables: Set<TableName>,
)
