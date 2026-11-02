package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.ExplainResult

data class SelectSqlStatement(
    val value: String,
) {
    init {
        val env =
            DatabaseEnvironment.current
                ?: throw IllegalStateException("No active DatabaseEnvironment — cannot validate SQL")
        val result = env.explain(value)
        require(result.isSuccess) {
            "Invalid SELECT SQL: ${(result as ExplainResult.Failure).error}"
        }
        // Fail closed: anything not positively identified as SELECT (DDL, COPY, MERGE, UNKNOWN) is rejected.
        require(env.statementType(value) == StatementType.SELECT) {
            "Not a SELECT statement"
        }
    }
}

data class InsertSqlStatement(
    val value: String,
) {
    init {
        val env =
            DatabaseEnvironment.current
                ?: throw IllegalStateException("No active DatabaseEnvironment — cannot validate SQL")
        val result = env.explain(value)
        require(result.isSuccess) {
            "Invalid INSERT SQL: ${(result as ExplainResult.Failure).error}"
        }
        require(env.statementType(value) == StatementType.INSERT) {
            "Not an INSERT statement"
        }
    }
}

data class UpdateSqlStatement(
    val value: String,
) {
    init {
        val env =
            DatabaseEnvironment.current
                ?: throw IllegalStateException("No active DatabaseEnvironment — cannot validate SQL")
        val result = env.explain(value)
        require(result.isSuccess) {
            "Invalid UPDATE SQL: ${(result as ExplainResult.Failure).error}"
        }
        require(env.statementType(value) == StatementType.UPDATE) {
            "Not an UPDATE statement"
        }
    }
}

data class DeleteSqlStatement(
    val value: String,
) {
    init {
        val env =
            DatabaseEnvironment.current
                ?: throw IllegalStateException("No active DatabaseEnvironment — cannot validate SQL")
        val result = env.explain(value)
        require(result.isSuccess) {
            "Invalid DELETE SQL: ${(result as ExplainResult.Failure).error}"
        }
        require(env.statementType(value) == StatementType.DELETE) {
            "Not a DELETE statement"
        }
    }
}

enum class StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    UNKNOWN,
}
