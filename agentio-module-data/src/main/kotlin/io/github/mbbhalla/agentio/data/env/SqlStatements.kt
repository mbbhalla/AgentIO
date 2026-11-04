package io.github.mbbhalla.agentio.data.env

/**
 * A single read-only `SELECT` that [environment] has validated. Created only by
 * [DatabaseEnvironment.selectStatement], and executable only on [environment].
 */
@ConsistentCopyVisibility
data class SelectSqlStatement internal constructor(
    val value: String,
    val environment: DatabaseEnvironment,
)

/** A single `INSERT` that [environment] has validated. Created only by [DatabaseEnvironment.insertStatement]. */
@ConsistentCopyVisibility
data class InsertSqlStatement internal constructor(
    val value: String,
    val environment: DatabaseEnvironment,
)

/** A single `UPDATE` that [environment] has validated. Created only by [DatabaseEnvironment.updateStatement]. */
@ConsistentCopyVisibility
data class UpdateSqlStatement internal constructor(
    val value: String,
    val environment: DatabaseEnvironment,
)

/** A single `DELETE` that [environment] has validated. Created only by [DatabaseEnvironment.deleteStatement]. */
@ConsistentCopyVisibility
data class DeleteSqlStatement internal constructor(
    val value: String,
    val environment: DatabaseEnvironment,
)

enum class StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    UNKNOWN,
}
