package io.github.mbbhalla.agentio.data.env

/**
 * A single read-only `SELECT` that [environment] has validated. Created only by
 * [DuckDbDatabaseEnvironment.selectStatement], and executable only on [environment].
 */
@ConsistentCopyVisibility
data class SelectSqlStatement internal constructor(
    val value: String,
    val environment: DuckDbDatabaseEnvironment,
)

/** A single `INSERT` that [environment] has validated. Created only by [DuckDbDatabaseEnvironment.insertStatement]. */
@ConsistentCopyVisibility
data class InsertSqlStatement internal constructor(
    val value: String,
    val environment: DuckDbDatabaseEnvironment,
)

/** A single `UPDATE` that [environment] has validated. Created only by [DuckDbDatabaseEnvironment.updateStatement]. */
@ConsistentCopyVisibility
data class UpdateSqlStatement internal constructor(
    val value: String,
    val environment: DuckDbDatabaseEnvironment,
)

/** A single `DELETE` that [environment] has validated. Created only by [DuckDbDatabaseEnvironment.deleteStatement]. */
@ConsistentCopyVisibility
data class DeleteSqlStatement internal constructor(
    val value: String,
    val environment: DuckDbDatabaseEnvironment,
)

enum class StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    UNKNOWN,
}
